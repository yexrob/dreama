// depth3d Jellyfin Plugin (Modern Jellyfin 10.10 / 10.11 / 12.x REST API)
// Supports authentication, home shelves (Resume / NextUp / Latest per library),
// library categories & browsing with sorting/filtering/pagination, series/seasons/episodes,
// search & suggestions, progress reporting, favorites, played status, external subtitles,
// and Direct Play (H.264/HEVC/AV1) vs HLS transcode quality selection.
//
// Security & protocol §11 compliance (S10):
// - Maps depth3d codec names ('avc' -> 'h264') for Jellyfin DeviceProfile
// - Never maps H.264 MVC to MV-HEVC
// - Never embeds api_key / ApiKey in image, stream, or subtitle URLs (uses Authorization header)
// - Keeps MediaSourceId lookup in a bounded in-memory LRU cache (never writes unbounded keys to ctx.storage)
// - Awaits all async ctx.storage.* calls

const TICKS_PER_MS = 10_000;
const PAGE_LIMIT = 40;
const MAX_MEDIA_SOURCE_CACHE = 64;

const QUALITY_PROFILES = {
  direct: { id: 'direct', label: '原画直连', bitrate: 0, maxWidth: 0, maxHeight: 0 },
  '1080p': { id: '1080p', label: '1080P (8 Mbps)', bitrate: 8_000_000, maxWidth: 1920, maxHeight: 1080 },
  '720p': { id: '720p', label: '720P (4 Mbps)', bitrate: 4_000_000, maxWidth: 1280, maxHeight: 720 },
  '480p': { id: '480p', label: '480P (1.5 Mbps)', bitrate: 1_500_000, maxWidth: 854, maxHeight: 480 },
};

// Bounded in-memory LRU map: itemId -> mediaSourceId (never persisted unboundedly to ctx.storage)
const mediaSourceCache = new Map();

function rememberMediaSourceId(itemId, mediaSourceId) {
  if (!itemId || !mediaSourceId) return;
  if (mediaSourceCache.has(itemId)) mediaSourceCache.delete(itemId);
  mediaSourceCache.set(itemId, String(mediaSourceId));
  if (mediaSourceCache.size > MAX_MEDIA_SOURCE_CACHE) {
    const oldest = mediaSourceCache.keys().next().value;
    mediaSourceCache.delete(oldest);
  }
}

function getServerBase(ctx) {
  const raw = (ctx.config.server || '').trim().replace(/\/+$/, '');
  if (!raw) {
    throw new ctx.Error('NeedsSetup', ctx.t('err_no_server'));
  }
  return raw;
}

async function getDeviceId(ctx) {
  let id = await ctx.storage.get('deviceId');
  if (!id) {
    id = `depth3d-${ctx.crypto.sha256(ctx.instanceId).slice(0, 16)}`;
    await ctx.storage.set('deviceId', id);
  }
  return id;
}

async function buildAuthHeader(ctx, token) {
  const device = encodeURIComponent(ctx.device.model || 'depth3d');
  const version = encodeURIComponent(ctx.device.appVersion || '1.0.0');
  const deviceId = encodeURIComponent(await getDeviceId(ctx));
  let header = `MediaBrowser Client="depth3d", Device="${device}", DeviceId="${deviceId}", Version="${version}"`;
  if (token) header += `, Token="${token}"`;
  return header;
}

async function requireSession(ctx) {
  const base = getServerBase(ctx);
  const session = await ctx.storage.get('session');
  if (!session || !session.accessToken || !session.userId) {
    throw new ctx.Error('NeedsAuth', ctx.t('err_needs_auth'));
  }
  const authHeader = await buildAuthHeader(ctx, session.accessToken);
  return { base, session, authHeader };
}

function stripApiKeyFromUrl(rawUrl) {
  const qIdx = rawUrl.indexOf('?');
  if (qIdx < 0) return rawUrl;
  const pathPart = rawUrl.slice(0, qIdx);
  const queryPart = rawUrl.slice(qIdx + 1);
  const kept = queryPart
    .split('&')
    .filter((pair) => {
      if (!pair) return false;
      const eq = pair.indexOf('=');
      const key = (eq >= 0 ? pair.slice(0, eq) : pair).toLowerCase();
      return key !== 'api_key' && key !== 'apikey';
    });
  return kept.length > 0 ? `${pathPart}?${kept.join('&')}` : pathPart;
}

function mapDeviceCodecsForJellyfin(codecs) {
  const list = Array.isArray(codecs) && codecs.length > 0 ? codecs : ['avc', 'hevc'];
  const mapped = [];
  for (const raw of list) {
    const c = String(raw || '').toLowerCase();
    const jf = c === 'avc' ? 'h264' : c;
    if (jf && !mapped.includes(jf)) mapped.push(jf);
  }
  return mapped.length > 0 ? mapped : ['h264', 'hevc'];
}

async function apiRequest(ctx, path, { method = 'GET', query = null, body = null, requireAuth = true } = {}) {
  const base = getServerBase(ctx);
  const session = requireAuth ? (await requireSession(ctx)).session : (await ctx.storage.get('session'));
  let url = `${base}${path.startsWith('/') ? path : `/${path}`}`;
  if (query) {
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') qs.set(k, String(v));
    }
    const qStr = qs.toString();
    if (qStr) url += (url.includes('?') ? '&' : '?') + qStr;
  }

  const headers = {
    Accept: 'application/json',
    Authorization: await buildAuthHeader(ctx, session?.accessToken),
  };

  let resp;
  try {
    resp = await ctx.fetch(url, {
      method,
      headers,
      body: body !== null ? body : undefined,
    });
  } catch (err) {
    ctx.log.warn(`Jellyfin request failed (${method} ${path}): ${err.message || err}`);
    throw new ctx.Error('Unavailable', ctx.t('err_connect'));
  }

  if (resp.status === 401) {
    if (requireAuth) {
      await ctx.storage.remove('session');
      throw new ctx.Error('NeedsAuth', ctx.t('err_needs_auth'));
    }
    throw new ctx.Error('AuthFailed', ctx.t('err_auth_failed'));
  }
  if (resp.status === 403) {
    throw new ctx.Error('Forbidden', '当前 Jellyfin 账号没有访问此内容的权限。');
  }
  if (resp.status === 404) {
    throw new ctx.Error('NotFound', 'Jellyfin 上已找不到这个内容。');
  }
  if (resp.status === 429) {
    const retry = parseInt(resp.headers['retry-after'] || '10', 10);
    throw new ctx.Error('RateLimited', '服务器限流，请稍后再试。', {
      retryAfterSec: Number.isFinite(retry) ? retry : 10,
    });
  }
  if (resp.status >= 500) {
    throw new ctx.Error('Unavailable', 'Jellyfin 服务器暂时不可用，请稍后重试。');
  }
  if (resp.status < 200 || resp.status >= 300) {
    throw new ctx.Error('Internal', `Jellyfin 返回了意外状态（${resp.status}）。`);
  }

  if (resp.status === 204) return null;
  const text = resp.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function buildImage(base, authHeader, itemId, imageType, tag, blurHashMap, maxWidth) {
  if (!tag) return undefined;
  // S10: Never embed api_key in URL; authenticate image loads via Authorization header
  const url = `${base}/Items/${encodeURIComponent(itemId)}/Images/${imageType}?tag=${encodeURIComponent(tag)}&maxWidth=${maxWidth}&quality=90`;
  const blurhash = blurHashMap?.[imageType]?.[tag];
  const img = { url, headers: { Authorization: authHeader } };
  if (blurhash) img.blurhash = blurhash;
  return img;
}

function mapKind(jfType) {
  switch (jfType) {
    case 'Movie': return 'movie';
    case 'Series': return 'series';
    case 'Season': return 'season';
    case 'Episode': return 'episode';
    case 'BoxSet': return 'collection';
    case 'Folder':
    case 'CollectionFolder':
    case 'UserView':
      return 'folder';
    case 'TvChannel':
    case 'LiveTvChannel':
      return 'live';
    case 'Person': return 'person';
    default: return 'video';
  }
}

function mapStereo(video3DFormat) {
  // S10: Do NOT map H.264 MVC to mvhevc
  switch (video3DFormat) {
    case 'HalfSideBySide':
    case 'FullSideBySide':
      return 'sbs';
    case 'HalfTopAndBottom':
    case 'FullTopAndBottom':
      return 'ou';
    default:
      return undefined;
  }
}

function formatSubtitleLine(raw) {
  const parts = [];
  if (raw.Type === 'Episode') {
    if (raw.SeriesName) parts.push(raw.SeriesName);
    if (raw.ParentIndexNumber != null && raw.IndexNumber != null) {
      parts.push(`S${raw.ParentIndexNumber}E${raw.IndexNumber}`);
    } else if (raw.IndexNumber != null) {
      parts.push(`第 ${raw.IndexNumber} 集`);
    }
  } else {
    if (raw.ProductionYear) parts.push(String(raw.ProductionYear));
    if (Array.isArray(raw.Genres) && raw.Genres.length > 0) {
      parts.push(raw.Genres.slice(0, 2).join(' / '));
    }
  }
  return parts.length > 0 ? parts.join(' · ') : undefined;
}

function extractBadges(raw) {
  const badges = [];
  const streams = raw.MediaSources?.[0]?.MediaStreams || raw.MediaStreams || [];
  const video = streams.find((s) => s.Type === 'Video');
  if (video) {
    if (video.Width >= 3800 || video.Height >= 2100) badges.push('4K');
    else if (video.Width >= 1900 || video.Height >= 1000) badges.push('1080P');
    if (video.VideoRange && video.VideoRange !== 'SDR') badges.push(String(video.VideoRange));
    if (video.Codec) badges.push(String(video.Codec).toUpperCase());
  }
  if (raw.Video3DFormat) badges.push('3D');
  return badges.length > 0 ? badges : undefined;
}

function mapItem(raw, base, authHeader, includeFullDetails = false) {
  const id = String(raw.Id);
  const kind = mapKind(raw.Type);
  const tags = raw.ImageTags || {};
  const blur = raw.ImageBlurHashes || {};

  const poster = buildImage(base, authHeader, id, 'Primary', tags.Primary, blur, 600);
  const thumb = buildImage(base, authHeader, id, 'Thumb', tags.Thumb, blur, 960)
    || (kind === 'episode' ? buildImage(base, authHeader, id, 'Primary', tags.Primary, blur, 960) : undefined);

  let backdrop;
  if (Array.isArray(raw.BackdropImageTags) && raw.BackdropImageTags.length > 0) {
    backdrop = buildImage(base, authHeader, id, 'Backdrop', raw.BackdropImageTags[0], blur, 1440);
  } else if (raw.ParentBackdropItemId && Array.isArray(raw.ParentBackdropImageTags) && raw.ParentBackdropImageTags.length > 0) {
    backdrop = buildImage(base, authHeader, raw.ParentBackdropItemId, 'Backdrop', raw.ParentBackdropImageTags[0], blur, 1440);
  }

  const item = {
    id,
    kind,
    title: raw.Name || id,
  };

  const sub = formatSubtitleLine(raw);
  if (sub) item.subtitle = sub;
  if (poster) item.poster = poster;
  if (thumb) item.thumb = thumb;
  if (backdrop) item.backdrop = backdrop;
  if (typeof raw.ProductionYear === 'number' && raw.ProductionYear >= 1800 && raw.ProductionYear <= 3000) {
    item.year = raw.ProductionYear;
  }
  if (raw.Overview) item.overview = String(raw.Overview);
  if (typeof raw.CommunityRating === 'number' && raw.CommunityRating >= 0 && raw.CommunityRating <= 10) {
    item.rating = Math.round(raw.CommunityRating * 10) / 10;
  }
  if (typeof raw.RunTimeTicks === 'number' && raw.RunTimeTicks > 0) {
    item.durationMs = Math.floor(raw.RunTimeTicks / TICKS_PER_MS);
  }
  if (raw.UserData) {
    if (typeof raw.UserData.PlaybackPositionTicks === 'number' && raw.UserData.PlaybackPositionTicks > 0) {
      item.progressMs = Math.floor(raw.UserData.PlaybackPositionTicks / TICKS_PER_MS);
    }
    if (typeof raw.UserData.Played === 'boolean') item.played = raw.UserData.Played;
    if (typeof raw.UserData.IsFavorite === 'boolean') item.favorite = raw.UserData.IsFavorite;
  }
  const stereo = mapStereo(raw.Video3DFormat);
  if (stereo) item.stereo = stereo;
  if (raw.ParentId || raw.SeasonId || raw.SeriesId) {
    item.parentId = String(raw.SeasonId || raw.ParentId || raw.SeriesId);
  }
  if (typeof raw.IndexNumber === 'number' && raw.IndexNumber >= 0) {
    item.index = raw.IndexNumber;
  }
  if (typeof raw.ChildCount === 'number' && raw.ChildCount >= 0) {
    item.childCount = raw.ChildCount;
  }

  const badges = extractBadges(raw);
  if (badges) item.badges = badges;

  if (includeFullDetails) {
    if (Array.isArray(raw.Genres) && raw.Genres.length > 0) {
      item.genres = raw.Genres.filter(Boolean).map(String);
    }
    if (Array.isArray(raw.People) && raw.People.length > 0) {
      item.people = raw.People.slice(0, 16)
        .filter((p) => p && p.Name)
        .map((p) => {
          const person = { name: String(p.Name) };
          if (p.Role || p.Type) person.role = String(p.Role || p.Type);
          if (p.Id) person.id = String(p.Id);
          if (p.Id && p.PrimaryImageTag) {
            person.image = buildImage(base, authHeader, p.Id, 'Primary', p.PrimaryImageTag, null, 300);
          }
          return person;
        });
    }
    const extra = [];
    if (Array.isArray(raw.Studios) && raw.Studios.length > 0) {
      extra.push({ label: '出品', value: raw.Studios.map((s) => s.Name).filter(Boolean).join(' / ') });
    }
    const ms = raw.MediaSources?.[0];
    if (ms?.Container) extra.push({ label: '容器', value: String(ms.Container).toUpperCase() });
    if (ms?.Bitrate) extra.push({ label: '码率', value: `${(ms.Bitrate / 1_000_000).toFixed(1)} Mbps` });
    if (extra.length > 0) item.extra = extra;
  }

  return item;
}

function mapSubtitleFormat(codec) {
  const c = String(codec || '').toLowerCase();
  if (c === 'srt' || c === 'subrip') return 'srt';
  if (c === 'vtt' || c === 'webvtt') return 'vtt';
  if (c === 'ass' || c === 'ssa') return 'ass';
  return null;
}

function collectionIcon(collectionType) {
  switch (String(collectionType || '').toLowerCase()) {
    case 'movies': return 'movie';
    case 'tvshows': return 'tv';
    case 'boxsets': return 'collection';
    case 'livetv': return 'live';
    default: return 'folder';
  }
}

export default {
  async login(ctx) {
    const base = getServerBase(ctx);
    const username = (ctx.config.username || '').trim();
    if (!username) {
      throw new ctx.Error('NeedsSetup', ctx.t('err_no_user'));
    }
    const password = ctx.config.password || '';

    const data = await apiRequest(ctx, '/Users/AuthenticateByName', {
      method: 'POST',
      body: { Username: username, Pw: password },
      requireAuth: false,
    });
    if (!data || !data.AccessToken || !data.User?.Id) {
      throw new ctx.Error('AuthFailed', ctx.t('err_auth_failed'));
    }

    const session = {
      accessToken: data.AccessToken,
      userId: data.User.Id,
      userName: data.User.Name || username,
      serverId: data.ServerId || '',
    };
    await ctx.storage.set('session', session);

    const authHeader = await buildAuthHeader(ctx, session.accessToken);
    const user = { name: session.userName };
    if (data.User.PrimaryImageTag) {
      user.avatar = {
        url: `${base}/Users/${encodeURIComponent(session.userId)}/Images/Primary?tag=${encodeURIComponent(data.User.PrimaryImageTag)}`,
        headers: { Authorization: authHeader },
      };
    }
    return { ok: true, user };
  },

  async logout(ctx) {
    try {
      await apiRequest(ctx, '/Sessions/Logout', { method: 'POST', requireAuth: true });
    } catch {
      // Ignore network errors when signing out locally
    }
    await ctx.storage.remove('session');
  },

  async home(ctx) {
    const { base, session, authHeader } = await requireSession(ctx);
    const shelves = [];

    // 1. Continue Watching (Resume) — modern Jellyfin route /UserItems/Resume
    const resume = await apiRequest(ctx, '/UserItems/Resume', {
      query: {
        userId: session.userId,
        Limit: 12,
        Recursive: 'true',
        MediaTypes: 'Video',
        Fields: 'Overview,Genres,MediaSources,UserData',
      },
    });
    if (Array.isArray(resume?.Items) && resume.Items.length > 0) {
      shelves.push({
        id: 'jf-resume',
        title: ctx.t('shelf_resume'),
        style: 'continue',
        items: resume.Items.map((it) => mapItem(it, base, authHeader)),
      });
    }

    // 2. Next Up (TV Episodes)
    const nextUp = await apiRequest(ctx, '/Shows/NextUp', {
      query: {
        userId: session.userId,
        Limit: 12,
        Fields: 'Overview,UserData',
      },
    });
    if (Array.isArray(nextUp?.Items) && nextUp.Items.length > 0) {
      shelves.push({
        id: 'jf-nextup',
        title: ctx.t('shelf_next_up'),
        style: 'thumb',
        items: nextUp.Items.map((it) => mapItem(it, base, authHeader)),
      });
    }

    // 3. Latest per library view — modern /UserViews + /Items/Latest
    const views = await apiRequest(ctx, '/UserViews', {
      query: { userId: session.userId },
    });
    const videoViews = (views?.Items || []).filter((v) =>
      !['music', 'books', 'photos'].includes(String(v.CollectionType || '').toLowerCase())
    );
    for (const view of videoViews.slice(0, 4)) {
      const latest = await apiRequest(ctx, '/Items/Latest', {
        query: {
          userId: session.userId,
          ParentId: view.Id,
          Limit: 16,
          Fields: 'Overview,Genres,UserData,MediaSources',
        },
      });
      if (Array.isArray(latest) && latest.length > 0) {
        shelves.push({
          id: `jf-latest-${view.Id}`,
          title: ctx.t('shelf_latest', { name: view.Name }),
          style: 'poster',
          more: String(view.Id),
          items: latest.map((it) => mapItem(it, base, authHeader)),
        });
      }
    }

    return shelves;
  },

  async categories(ctx) {
    const { session } = await requireSession(ctx);
    const views = await apiRequest(ctx, '/UserViews', {
      query: { userId: session.userId },
    });
    return (views?.Items || [])
      .filter((v) => !['music', 'books', 'photos'].includes(String(v.CollectionType || '').toLowerCase()))
      .map((v) => ({
        id: String(v.Id),
        title: String(v.Name || v.Id),
        icon: collectionIcon(v.CollectionType),
      }));
  },

  async browse(ctx, { categoryId = '', cursor = null, sort = null, filters = null } = {}) {
    const { base, session, authHeader } = await requireSession(ctx);

    if (!categoryId) {
      const views = await apiRequest(ctx, '/UserViews', {
        query: { userId: session.userId },
      });
      const items = (views?.Items || [])
        .filter((v) => !['music', 'books', 'photos'].includes(String(v.CollectionType || '').toLowerCase()))
        .map((v) => mapItem(v, base, authHeader));
      return { items, next: null };
    }

    const startIndex = cursor ? Math.max(0, parseInt(String(cursor), 10) || 0) : 0;
    let sortBy = 'SortName';
    let sortOrder = 'Ascending';
    if (sort === 'date_desc') { sortBy = 'DateCreated'; sortOrder = 'Descending'; }
    else if (sort === 'year_desc') { sortBy = 'PremiereDate'; sortOrder = 'Descending'; }
    else if (sort === 'rating_desc') { sortBy = 'CommunityRating'; sortOrder = 'Descending'; }

    const query = {
      userId: session.userId,
      ParentId: categoryId,
      StartIndex: startIndex,
      Limit: PAGE_LIMIT,
      SortBy: sortBy,
      SortOrder: sortOrder,
      Fields: 'Overview,Genres,MediaSources,UserData,ChildCount',
    };
    if (filters?.status) query.Filters = filters.status;
    if (filters?.type) query.IncludeItemTypes = filters.type;

    const data = await apiRequest(ctx, '/Items', { query });
    const rawItems = Array.isArray(data?.Items) ? data.Items : [];
    const total = Number(data?.TotalRecordCount ?? rawItems.length);
    const nextIdx = startIndex + rawItems.length;
    return {
      items: rawItems.map((it) => mapItem(it, base, authHeader)),
      next: nextIdx < total ? String(nextIdx) : null,
    };
  },

  filters() {
    return {
      sorts: [
        { value: 'name_asc', label: '按名称' },
        { value: 'date_desc', label: '最近添加' },
        { value: 'year_desc', label: '上映年份' },
        { value: 'rating_desc', label: '评分最高' },
      ],
      filters: [
        {
          key: 'status',
          label: '观看状态',
          options: [
            { value: 'IsUnplayed', label: '未观看' },
            { value: 'IsPlayed', label: '已观看' },
            { value: 'IsFavorite', label: '已收藏' },
          ],
        },
        {
          key: 'type',
          label: '内容类型',
          options: [
            { value: 'Movie', label: '电影' },
            { value: 'Series', label: '剧集' },
            { value: 'BoxSet', label: '合集' },
          ],
        },
      ],
    };
  },

  async detail(ctx, { itemId }) {
    const { base, session, authHeader } = await requireSession(ctx);
    const raw = await apiRequest(ctx, `/Items/${encodeURIComponent(itemId)}`, {
      query: {
        userId: session.userId,
        Fields: 'Overview,Genres,People,MediaSources,MediaStreams,Studios,ChildCount,UserData',
      },
    });
    const item = mapItem(raw, base, authHeader, true);

    if (raw.Type === 'Series') {
      const seasonsResp = await apiRequest(ctx, `/Shows/${encodeURIComponent(itemId)}/Seasons`, {
        query: { userId: session.userId, Fields: 'Overview,ChildCount,UserData' },
      });
      item.seasons = (seasonsResp?.Items || []).map((s) => mapItem(s, base, authHeader));
    } else if (raw.Type === 'Season' && raw.SeriesId) {
      const epsResp = await apiRequest(ctx, `/Shows/${encodeURIComponent(raw.SeriesId)}/Episodes`, {
        query: {
          SeasonId: itemId,
          userId: session.userId,
          Fields: 'Overview,UserData,MediaSources',
        },
      });
      item.episodes = (epsResp?.Items || []).map((e) => mapItem(e, base, authHeader));
    }

    if (raw.Type === 'Movie' || raw.Type === 'Series') {
      try {
        const similar = await apiRequest(ctx, `/Items/${encodeURIComponent(itemId)}/Similar`, {
          query: { userId: session.userId, Limit: 8, Fields: 'UserData' },
        });
        if (Array.isArray(similar?.Items) && similar.Items.length > 0) {
          item.related = similar.Items.map((r) => mapItem(r, base, authHeader));
        }
      } catch {
        // Similar items are optional
      }
    }

    return item;
  },

  async playback(ctx, { itemId, quality = null, audio = null }) {
    const { base, session, authHeader } = await requireSession(ctx);
    const selectedQuality = quality && QUALITY_PROFILES[quality]
      ? quality
      : (QUALITY_PROFILES[ctx.config.quality] ? ctx.config.quality : 'direct');
    const profile = QUALITY_PROFILES[selectedQuality];
    const jfCodecs = mapDeviceCodecsForJellyfin(ctx.device.codecs);
    const wantTranscodeProfile = selectedQuality !== 'direct';
    const maxBitrate = profile.bitrate > 0 ? profile.bitrate : 120_000_000;

    const transcodeConditions = [];
    if (profile.maxWidth > 0) {
      transcodeConditions.push({
        Condition: 'LessThanEqual',
        Property: 'Width',
        Value: String(profile.maxWidth),
        IsRequired: false,
      });
    }
    if (profile.maxHeight > 0) {
      transcodeConditions.push({
        Condition: 'LessThanEqual',
        Property: 'Height',
        Value: String(profile.maxHeight),
        IsRequired: false,
      });
    }

    const body = {
      UserId: session.userId,
      MaxStreamingBitrate: maxBitrate,
      AutoOpenLiveStream: true,
      DeviceProfile: {
        Name: 'depth3d',
        MaxStreamingBitrate: maxBitrate,
        DirectPlayProfiles: wantTranscodeProfile
          ? []
          : [
              {
                Container: 'mp4,m4v,mkv,webm,mov,ts',
                Type: 'Video',
                VideoCodec: jfCodecs.join(','),
                AudioCodec: 'aac,ac3,eac3,mp3,opus,flac',
              },
            ],
        TranscodingProfiles: [
          {
            Container: 'ts',
            Type: 'Video',
            Protocol: 'hls',
            VideoCodec: jfCodecs.includes('hevc') ? 'h264,hevc' : 'h264',
            AudioCodec: 'aac',
            Context: 'Streaming',
          },
        ],
        CodecProfiles: transcodeConditions.length > 0
          ? [
              {
                Type: 'Video',
                Conditions: transcodeConditions,
              },
            ]
          : [],
        SubtitleProfiles: [
          { Format: 'srt', Method: 'External' },
          { Format: 'vtt', Method: 'External' },
          { Format: 'ass', Method: 'External' },
          { Format: 'ssa', Method: 'External' },
        ],
      },
    };
    if (audio !== null && audio !== undefined && String(audio).trim() !== '') {
      const audioIdx = parseInt(String(audio), 10);
      if (Number.isFinite(audioIdx)) body.AudioStreamIndex = audioIdx;
    }

    const pbResp = await apiRequest(ctx, `/Items/${encodeURIComponent(itemId)}/PlaybackInfo`, {
      method: 'POST',
      query: { userId: session.userId },
      body,
    });

    const source = pbResp?.MediaSources?.[0];
    if (!source) {
      throw new ctx.Error('Unsupported', 'Jellyfin 未返回可用的媒体流，无法播放。');
    }

    const mediaSourceId = source.Id || itemId;
    const playSessionId = pbResp.PlaySessionId || `jf-${Date.now()}`;
    const deviceId = await getDeviceId(ctx);
    const headers = {
      Authorization: authHeader,
    };

    const streams = Array.isArray(source.MediaStreams) ? source.MediaStreams : [];
    const audios = streams
      .filter((s) => s.Type === 'Audio')
      .map((s) => ({
        id: String(s.Index),
        label: s.DisplayTitle || s.Title || s.Language || `音轨 ${s.Index}`,
        ...(s.Language ? { lang: String(s.Language) } : {}),
      }));

    const subtitles = [];
    for (const s of streams) {
      if (s.Type !== 'Subtitle') continue;
      const fmt = mapSubtitleFormat(s.Codec);
      if (!fmt) continue;
      const rawSubUrl = s.DeliveryUrl
        ? (s.DeliveryUrl.startsWith('http') ? s.DeliveryUrl : `${base}${s.DeliveryUrl}`)
        : `${base}/Videos/${encodeURIComponent(itemId)}/${encodeURIComponent(mediaSourceId)}/Subtitles/${s.Index}/0/Stream.${fmt}`;
      subtitles.push({
        id: String(s.Index),
        label: s.DisplayTitle || s.Title || s.Language || `字幕 ${s.Index}`,
        ...(s.Language ? { lang: String(s.Language) } : {}),
        url: stripApiKeyFromUrl(rawSubUrl),
        format: fmt,
        headers,
        ...(s.IsDefault ? { default: true } : {}),
      });
    }

    let url;
    let mime;
    const useTranscode = wantTranscodeProfile || source.SupportsDirectPlay === false;
    if (!useTranscode) {
      const qs = new URLSearchParams({
        Static: 'true',
        MediaSourceId: mediaSourceId,
        PlaySessionId: playSessionId,
        DeviceId: deviceId,
      });
      url = `${base}/Videos/${encodeURIComponent(itemId)}/stream?${qs.toString()}`;
    } else if (source.TranscodingUrl) {
      const fullTranscodeUrl = source.TranscodingUrl.startsWith('http')
        ? source.TranscodingUrl
        : `${base}${source.TranscodingUrl}`;
      url = stripApiKeyFromUrl(fullTranscodeUrl);
      mime = 'application/x-mpegURL';
    } else {
      const qs = new URLSearchParams({
        MediaSourceId: mediaSourceId,
        PlaySessionId: playSessionId,
        DeviceId: deviceId,
        VideoCodec: jfCodecs.includes('hevc') ? 'h264,hevc' : 'h264',
        AudioCodec: 'aac',
        VideoBitrate: String(profile.bitrate || 8_000_000),
        MaxWidth: String(profile.maxWidth || 1920),
        MaxHeight: String(profile.maxHeight || 1080),
      });
      if (audio) qs.set('AudioStreamIndex', String(audio));
      url = `${base}/Videos/${encodeURIComponent(itemId)}/master.m3u8?${qs.toString()}`;
      mime = 'application/x-mpegURL';
    }

    const qualities = Object.values(QUALITY_PROFILES).map((q) => ({
      id: q.id,
      label: q.label,
      selected: q.id === selectedQuality,
    }));

    const result = {
      url,
      headers,
      qualities,
      session: playSessionId,
    };
    if (typeof source.RunTimeTicks === 'number' && source.RunTimeTicks > 0) {
      result.durationMs = Math.floor(source.RunTimeTicks / TICKS_PER_MS);
    }
    if (mime) result.mime = mime;
    if (subtitles.length > 0) result.subtitles = subtitles;
    if (audios.length > 0) result.audios = audios;
    if (source.IsInfiniteStream) result.live = true;
    const stereo = mapStereo(source.Video3DFormat);
    if (stereo) result.stereo = stereo;

    rememberMediaSourceId(itemId, mediaSourceId);
    return result;
  },

  async search(ctx, { query = '', cursor = null, kind = null } = {}) {
    const { base, session, authHeader } = await requireSession(ctx);
    const startIndex = cursor ? Math.max(0, parseInt(String(cursor), 10) || 0) : 0;
    const typeMap = {
      movie: 'Movie',
      series: 'Series',
      episode: 'Episode',
      collection: 'BoxSet',
      video: 'Video',
    };
    const data = await apiRequest(ctx, '/Items', {
      query: {
        userId: session.userId,
        SearchTerm: query,
        Recursive: 'true',
        IncludeItemTypes: (kind && typeMap[kind]) || 'Movie,Series,Episode,Video,BoxSet',
        StartIndex: startIndex,
        Limit: 30,
        Fields: 'Overview,Genres,UserData,MediaSources',
      },
    });
    const rawItems = Array.isArray(data?.Items) ? data.Items : [];
    const total = Number(data?.TotalRecordCount ?? rawItems.length);
    const nextIdx = startIndex + rawItems.length;
    return {
      items: rawItems.map((it) => mapItem(it, base, authHeader)),
      next: nextIdx < total ? String(nextIdx) : null,
    };
  },

  async suggest(ctx, { query = '' } = {}) {
    const { session } = await requireSession(ctx);
    const data = await apiRequest(ctx, '/Search/Hints', {
      query: { searchTerm: query, userId: session.userId, limit: 8 },
    });
    return (data?.SearchHints || []).map((h) => String(h.Name)).filter(Boolean);
  },

  async reportProgress(ctx, { itemId, positionMs = 0, durationMs = 0, event = 'progress', session = null }) {
    await requireSession(ctx);
    const ticks = Math.max(0, Math.floor(Number(positionMs) * TICKS_PER_MS));
    const mediaSourceId = mediaSourceCache.get(itemId) || itemId;
    const payload = {
      ItemId: itemId,
      MediaSourceId: mediaSourceId,
      PositionTicks: ticks,
      IsPaused: event === 'pause',
      PlaySessionId: session || undefined,
    };
    if (event === 'start') {
      await apiRequest(ctx, '/Sessions/Playing', { method: 'POST', body: payload });
    } else if (event === 'stop' || event === 'finish') {
      await apiRequest(ctx, '/Sessions/Playing/Stopped', { method: 'POST', body: payload });
    } else {
      await apiRequest(ctx, '/Sessions/Playing/Progress', { method: 'POST', body: payload });
    }
  },

  async setFavorite(ctx, { itemId, favorite }) {
    const { session } = await requireSession(ctx);
    await apiRequest(ctx, `/UserFavoriteItems/${encodeURIComponent(itemId)}`, {
      method: favorite ? 'POST' : 'DELETE',
      query: { userId: session.userId },
    });
  },

  async setPlayed(ctx, { itemId, played }) {
    const { session } = await requireSession(ctx);
    await apiRequest(ctx, `/UserPlayedItems/${encodeURIComponent(itemId)}`, {
      method: played ? 'POST' : 'DELETE',
      query: { userId: session.userId },
    });
  },
};
