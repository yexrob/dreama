// depth3d Jellyfin Plugin (Official Jellyfin REST API)
// Supports authentication, home shelves (Resume / NextUp / Latest per library),
// library categories & browsing with sorting/filtering/pagination, series/seasons/episodes,
// search & suggestions, progress reporting, favorites, played status, external subtitles,
// and Direct Play vs HLS transcode quality selection.

const TICKS_PER_MS = 10_000;
const PAGE_LIMIT = 40;

const QUALITY_PROFILES = {
  direct: { id: 'direct', label: '原画直连', bitrate: 0, maxWidth: 0, maxHeight: 0 },
  '1080p': { id: '1080p', label: '1080P (8 Mbps)', bitrate: 8_000_000, maxWidth: 1920, maxHeight: 1080 },
  '720p': { id: '720p', label: '720P (4 Mbps)', bitrate: 4_000_000, maxWidth: 1280, maxHeight: 720 },
  '480p': { id: '480p', label: '480P (1.5 Mbps)', bitrate: 1_500_000, maxWidth: 854, maxHeight: 480 },
};

function getServerBase(ctx) {
  const raw = (ctx.config.server || '').trim().replace(/\/+$/, '');
  if (!raw) {
    throw new ctx.Error('NeedsSetup', ctx.t('err_no_server'));
  }
  return raw;
}

function getDeviceId(ctx) {
  let id = ctx.storage.get('deviceId');
  if (!id) {
    id = `depth3d-${ctx.crypto.sha256(ctx.instanceId).slice(0, 16)}`;
    ctx.storage.set('deviceId', id);
  }
  return id;
}

function buildAuthHeader(ctx, token) {
  const device = encodeURIComponent(ctx.device.model || 'depth3d');
  const version = encodeURIComponent(ctx.device.appVersion || '1.0.0');
  const deviceId = encodeURIComponent(getDeviceId(ctx));
  let header = `MediaBrowser Client="depth3d", Device="${device}", DeviceId="${deviceId}", Version="${version}"`;
  if (token) header += `, Token="${token}"`;
  return header;
}

function requireSession(ctx) {
  const base = getServerBase(ctx);
  const session = ctx.storage.get('session');
  if (!session || !session.accessToken || !session.userId) {
    throw new ctx.Error('NeedsAuth', ctx.t('err_needs_auth'));
  }
  return { base, session };
}

async function apiRequest(ctx, path, { method = 'GET', query = null, body = null, requireAuth = true } = {}) {
  const base = getServerBase(ctx);
  const session = requireAuth ? requireSession(ctx).session : ctx.storage.get('session');
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
    Authorization: buildAuthHeader(ctx, session?.accessToken),
  };
  if (session?.accessToken) {
    headers['X-Emby-Token'] = session.accessToken;
  }

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
      ctx.storage.remove('session');
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

function buildImage(base, token, itemId, imageType, tag, blurHashMap, maxWidth) {
  if (!tag) return undefined;
  const url = `${base}/Items/${encodeURIComponent(itemId)}/Images/${imageType}?tag=${encodeURIComponent(tag)}&maxWidth=${maxWidth}&quality=90&api_key=${encodeURIComponent(token)}`;
  const blurhash = blurHashMap?.[imageType]?.[tag];
  if (blurhash) {
    return { url, blurhash };
  }
  return url;
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
  switch (video3DFormat) {
    case 'HalfSideBySide':
    case 'FullSideBySide':
      return 'sbs';
    case 'HalfTopAndBottom':
    case 'FullTopAndBottom':
      return 'ou';
    case 'MVC':
      return 'mvhevc';
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

function mapItem(raw, base, token, includeFullDetails = false) {
  const id = String(raw.Id);
  const kind = mapKind(raw.Type);
  const tags = raw.ImageTags || {};
  const blur = raw.ImageBlurHashes || {};

  const poster = buildImage(base, token, id, 'Primary', tags.Primary, blur, 600);
  const thumb = buildImage(base, token, id, 'Thumb', tags.Thumb, blur, 960)
    || (kind === 'episode' ? buildImage(base, token, id, 'Primary', tags.Primary, blur, 960) : undefined);

  let backdrop;
  if (Array.isArray(raw.BackdropImageTags) && raw.BackdropImageTags.length > 0) {
    backdrop = buildImage(base, token, id, 'Backdrop', raw.BackdropImageTags[0], blur, 1440);
  } else if (raw.ParentBackdropItemId && Array.isArray(raw.ParentBackdropImageTags) && raw.ParentBackdropImageTags.length > 0) {
    backdrop = buildImage(base, token, raw.ParentBackdropItemId, 'Backdrop', raw.ParentBackdropImageTags[0], blur, 1440);
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
            person.image = buildImage(base, token, p.Id, 'Primary', p.PrimaryImageTag, null, 300);
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
    ctx.storage.set('session', session);

    const user = { name: session.userName };
    if (data.User.PrimaryImageTag) {
      user.avatar = `${base}/Users/${encodeURIComponent(session.userId)}/Images/Primary?tag=${encodeURIComponent(data.User.PrimaryImageTag)}&api_key=${encodeURIComponent(session.accessToken)}`;
    }
    return { ok: true, user };
  },

  async logout(ctx) {
    try {
      await apiRequest(ctx, '/Sessions/Logout', { method: 'POST', requireAuth: true });
    } catch {
      // Ignore network errors when signing out locally
    }
    ctx.storage.remove('session');
  },

  async home(ctx) {
    const { base, session } = requireSession(ctx);
    const uid = encodeURIComponent(session.userId);
    const shelves = [];

    // 1. Continue Watching (Resume)
    const resume = await apiRequest(ctx, `/Users/${uid}/Items/Resume`, {
      query: {
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
        items: resume.Items.map((it) => mapItem(it, base, session.accessToken)),
      });
    }

    // 2. Next Up (TV Episodes)
    const nextUp = await apiRequest(ctx, '/Shows/NextUp', {
      query: {
        UserId: session.userId,
        Limit: 12,
        Fields: 'Overview,UserData',
      },
    });
    if (Array.isArray(nextUp?.Items) && nextUp.Items.length > 0) {
      shelves.push({
        id: 'jf-nextup',
        title: ctx.t('shelf_next_up'),
        style: 'thumb',
        items: nextUp.Items.map((it) => mapItem(it, base, session.accessToken)),
      });
    }

    // 3. Latest per library view
    const views = await apiRequest(ctx, `/Users/${uid}/Views`);
    const videoViews = (views?.Items || []).filter((v) =>
      !['music', 'books', 'photos'].includes(String(v.CollectionType || '').toLowerCase())
    );
    for (const view of videoViews.slice(0, 4)) {
      const latest = await apiRequest(ctx, `/Users/${uid}/Items/Latest`, {
        query: {
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
          items: latest.map((it) => mapItem(it, base, session.accessToken)),
        });
      }
    }

    return shelves;
  },

  async categories(ctx) {
    const { session } = requireSession(ctx);
    const uid = encodeURIComponent(session.userId);
    const views = await apiRequest(ctx, `/Users/${uid}/Views`);
    return (views?.Items || [])
      .filter((v) => !['music', 'books', 'photos'].includes(String(v.CollectionType || '').toLowerCase()))
      .map((v) => ({
        id: String(v.Id),
        title: String(v.Name || v.Id),
        icon: collectionIcon(v.CollectionType),
      }));
  },

  async browse(ctx, { categoryId = '', cursor = null, sort = null, filters = null } = {}) {
    const { base, session } = requireSession(ctx);
    const uid = encodeURIComponent(session.userId);

    if (!categoryId) {
      const views = await apiRequest(ctx, `/Users/${uid}/Views`);
      const items = (views?.Items || [])
        .filter((v) => !['music', 'books', 'photos'].includes(String(v.CollectionType || '').toLowerCase()))
        .map((v) => mapItem(v, base, session.accessToken));
      return { items, next: null };
    }

    const startIndex = cursor ? Math.max(0, parseInt(String(cursor), 10) || 0) : 0;
    let sortBy = 'SortName';
    let sortOrder = 'Ascending';
    if (sort === 'date_desc') { sortBy = 'DateCreated'; sortOrder = 'Descending'; }
    else if (sort === 'year_desc') { sortBy = 'PremiereDate'; sortOrder = 'Descending'; }
    else if (sort === 'rating_desc') { sortBy = 'CommunityRating'; sortOrder = 'Descending'; }

    const query = {
      ParentId: categoryId,
      StartIndex: startIndex,
      Limit: PAGE_LIMIT,
      SortBy: sortBy,
      SortOrder: sortOrder,
      Fields: 'Overview,Genres,MediaSources,UserData,ChildCount',
    };
    if (filters?.status) query.Filters = filters.status;
    if (filters?.type) query.IncludeItemTypes = filters.type;

    const data = await apiRequest(ctx, `/Users/${uid}/Items`, { query });
    const rawItems = Array.isArray(data?.Items) ? data.Items : [];
    const total = Number(data?.TotalRecordCount ?? rawItems.length);
    const nextIdx = startIndex + rawItems.length;
    return {
      items: rawItems.map((it) => mapItem(it, base, session.accessToken)),
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
    const { base, session } = requireSession(ctx);
    const uid = encodeURIComponent(session.userId);
    const raw = await apiRequest(ctx, `/Users/${uid}/Items/${encodeURIComponent(itemId)}`, {
      query: { Fields: 'Overview,Genres,People,MediaSources,MediaStreams,Studios,ChildCount,UserData' },
    });
    const item = mapItem(raw, base, session.accessToken, true);

    if (raw.Type === 'Series') {
      const seasonsResp = await apiRequest(ctx, `/Shows/${encodeURIComponent(itemId)}/Seasons`, {
        query: { UserId: session.userId, Fields: 'Overview,ChildCount,UserData' },
      });
      item.seasons = (seasonsResp?.Items || []).map((s) => mapItem(s, base, session.accessToken));
    } else if (raw.Type === 'Season' && raw.SeriesId) {
      const epsResp = await apiRequest(ctx, `/Shows/${encodeURIComponent(raw.SeriesId)}/Episodes`, {
        query: {
          SeasonId: itemId,
          UserId: session.userId,
          Fields: 'Overview,UserData,MediaSources',
        },
      });
      item.episodes = (epsResp?.Items || []).map((e) => mapItem(e, base, session.accessToken));
    }

    if (raw.Type === 'Movie' || raw.Type === 'Series') {
      try {
        const similar = await apiRequest(ctx, `/Items/${encodeURIComponent(itemId)}/Similar`, {
          query: { UserId: session.userId, Limit: 8, Fields: 'UserData' },
        });
        if (Array.isArray(similar?.Items) && similar.Items.length > 0) {
          item.related = similar.Items.map((r) => mapItem(r, base, session.accessToken));
        }
      } catch {
        // Similar items are optional
      }
    }

    return item;
  },

  async playback(ctx, { itemId, quality = null, audio = null }) {
    const { base, session } = requireSession(ctx);
    const selectedQuality = quality && QUALITY_PROFILES[quality]
      ? quality
      : (QUALITY_PROFILES[ctx.config.quality] ? ctx.config.quality : 'direct');
    const profile = QUALITY_PROFILES[selectedQuality];
    const codecs = Array.isArray(ctx.device.codecs) && ctx.device.codecs.length > 0
      ? ctx.device.codecs
      : ['avc', 'hevc'];

    const pbResp = await apiRequest(ctx, `/Items/${encodeURIComponent(itemId)}/PlaybackInfo`, {
      method: 'POST',
      query: { UserId: session.userId },
      body: {
        UserId: session.userId,
        MaxStreamingBitrate: profile.bitrate > 0 ? profile.bitrate : 120_000_000,
        AutoOpenLiveStream: true,
        DeviceProfile: {
          Name: 'depth3d',
          MaxStreamingBitrate: profile.bitrate > 0 ? profile.bitrate : 120_000_000,
          DirectPlayProfiles: [
            {
              Container: 'mp4,m4v,mkv,webm,mov,ts',
              Type: 'Video',
              VideoCodec: codecs.join(','),
              AudioCodec: 'aac,ac3,eac3,mp3,opus,flac',
            },
          ],
          TranscodingProfiles: [
            {
              Container: 'ts',
              Type: 'Video',
              Protocol: 'hls',
              VideoCodec: codecs.includes('hevc') ? 'hevc,h264' : 'h264',
              AudioCodec: 'aac',
              Context: 'Streaming',
            },
          ],
          SubtitleProfiles: [
            { Format: 'srt', Method: 'External' },
            { Format: 'vtt', Method: 'External' },
            { Format: 'ass', Method: 'External' },
            { Format: 'ssa', Method: 'External' },
          ],
        },
      },
    });

    const source = pbResp?.MediaSources?.[0];
    if (!source) {
      throw new ctx.Error('Unsupported', 'Jellyfin 未返回可用的媒体流，无法播放。');
    }

    const mediaSourceId = source.Id || itemId;
    const playSessionId = pbResp.PlaySessionId || `jf-${Date.now()}`;
    const headers = {
      'X-Emby-Token': session.accessToken,
      Authorization: buildAuthHeader(ctx, session.accessToken),
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
      const subUrl = s.DeliveryUrl
        ? (s.DeliveryUrl.startsWith('http') ? s.DeliveryUrl : `${base}${s.DeliveryUrl}`)
        : `${base}/Videos/${encodeURIComponent(itemId)}/${encodeURIComponent(mediaSourceId)}/Subtitles/${s.Index}/0/Stream.${fmt}?api_key=${encodeURIComponent(session.accessToken)}`;
      subtitles.push({
        id: String(s.Index),
        label: s.DisplayTitle || s.Title || s.Language || `字幕 ${s.Index}`,
        ...(s.Language ? { lang: String(s.Language) } : {}),
        url: subUrl,
        format: fmt,
        headers,
        ...(s.IsDefault ? { default: true } : {}),
      });
    }

    let url;
    let mime;
    const wantTranscode = selectedQuality !== 'direct' || source.SupportsDirectPlay === false;
    if (!wantTranscode) {
      const qs = new URLSearchParams({
        Static: 'true',
        MediaSourceId: mediaSourceId,
        PlaySessionId: playSessionId,
        DeviceId: getDeviceId(ctx),
        api_key: session.accessToken,
      });
      url = `${base}/Videos/${encodeURIComponent(itemId)}/stream?${qs.toString()}`;
    } else if (source.TranscodingUrl) {
      url = source.TranscodingUrl.startsWith('http') ? source.TranscodingUrl : `${base}${source.TranscodingUrl}`;
      mime = 'application/x-mpegURL';
    } else {
      const qs = new URLSearchParams({
        MediaSourceId: mediaSourceId,
        PlaySessionId: playSessionId,
        DeviceId: getDeviceId(ctx),
        VideoCodec: codecs.includes('hevc') ? 'hevc,h264' : 'h264',
        AudioCodec: 'aac',
        VideoBitrate: String(profile.bitrate || 8_000_000),
        MaxWidth: String(profile.maxWidth || 1920),
        MaxHeight: String(profile.maxHeight || 1080),
        api_key: session.accessToken,
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
    if (mime) result.mime = mime;
    if (subtitles.length > 0) result.subtitles = subtitles;
    if (audios.length > 0) result.audios = audios;
    if (source.IsInfiniteStream) result.live = true;
    const stereo = mapStereo(source.Video3DFormat);
    if (stereo) result.stereo = stereo;

    ctx.storage.set(`ms_${itemId}`, mediaSourceId);
    return result;
  },

  async search(ctx, { query = '', cursor = null, kind = null } = {}) {
    const { base, session } = requireSession(ctx);
    const uid = encodeURIComponent(session.userId);
    const startIndex = cursor ? Math.max(0, parseInt(String(cursor), 10) || 0) : 0;
    const typeMap = {
      movie: 'Movie',
      series: 'Series',
      episode: 'Episode',
      collection: 'BoxSet',
      video: 'Video',
    };
    const data = await apiRequest(ctx, `/Users/${uid}/Items`, {
      query: {
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
      items: rawItems.map((it) => mapItem(it, base, session.accessToken)),
      next: nextIdx < total ? String(nextIdx) : null,
    };
  },

  async suggest(ctx, { query = '' } = {}) {
    const { session } = requireSession(ctx);
    const data = await apiRequest(ctx, '/Search/Hints', {
      query: { SearchTerm: query, UserId: session.userId, Limit: 8 },
    });
    return (data?.SearchHints || []).map((h) => String(h.Name)).filter(Boolean);
  },

  async reportProgress(ctx, { itemId, positionMs = 0, durationMs = 0, event = 'progress', session = null }) {
    requireSession(ctx);
    const ticks = Math.max(0, Math.floor(Number(positionMs) * TICKS_PER_MS));
    const mediaSourceId = ctx.storage.get(`ms_${itemId}`) || itemId;
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
    const { session } = requireSession(ctx);
    const uid = encodeURIComponent(session.userId);
    await apiRequest(ctx, `/Users/${uid}/FavoriteItems/${encodeURIComponent(itemId)}`, {
      method: favorite ? 'POST' : 'DELETE',
    });
  },

  async setPlayed(ctx, { itemId, played }) {
    const { session } = requireSession(ctx);
    const uid = encodeURIComponent(session.userId);
    await apiRequest(ctx, `/Users/${uid}/PlayedItems/${encodeURIComponent(itemId)}`, {
      method: played ? 'POST' : 'DELETE',
    });
  },
};
