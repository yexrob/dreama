// depth3d Protocol v1 Reference Plugin (updated for protocol §11)
// Deterministic fixture plugin covering every protocol field, capability,
// error code, async storage/cookies, login flow (form / qr / device), and
// video-platform pattern (separated DASH + Referer + durationMs, locked
// quality reason, multi-part episodes, segmented danmaku with hex & integer
// colors, and expiring stream URL refresh).

const CDN = 'https://media.cdn.reference.invalid';
const IMG = 'https://img.cdn.reference.invalid';

const CATALOG = {
  'movie-full': {
    id: 'movie-full',
    kind: 'movie',
    title: '星际穿越 · 全字段测试电影',
    subtitle: '2014 · 科幻 / 冒险 · 4K SBS',
    poster: {
      url: `${IMG}/posters/movie-full.jpg`,
      headers: { Referer: 'https://api.reference.invalid/' },
      blurhash: 'LEHV6nWB2yk8pyo0adR*.7kCMdnj',
    },
    thumb: `${IMG}/thumbs/movie-full.jpg`,
    backdrop: {
      url: `${IMG}/backdrops/movie-full.jpg`,
      blurhash: 'L6PZfSi_.AyE_3t7t7R**0o#DgR4',
    },
    year: 2014,
    overview: '用于验证 depth3d 插件协议所有可选字段的完整影片条目。',
    rating: 9.4,
    durationMs: 10_140_000,
    progressMs: 1_234_000,
    played: false,
    favorite: true,
    badges: ['4K', 'HDR', 'SBS'],
    stereo: 'sbs',
    index: 1,
    people: [
      { name: '克里斯托弗·诺兰', role: '导演', id: 'person-1', image: `${IMG}/people/nolan.jpg` },
      { name: '马修·麦康纳', role: '库珀', id: 'person-2' },
    ],
    genres: ['科幻', '冒险', '剧情'],
    extra: [
      { label: '音频', value: 'Dolby Atmos · 5.1' },
      { label: '分辨率', value: '3840×2160 · 24fps' },
    ],
  },
  'series-1': {
    id: 'series-1',
    kind: 'series',
    title: '深空纪元',
    subtitle: '2025 · 科幻剧集 · 共 2 季',
    poster: `${IMG}/posters/series-1.jpg`,
    backdrop: `${IMG}/backdrops/series-1.jpg`,
    year: 2025,
    overview: '涵盖剧集、季、单集层级关系的测试条目。',
    rating: 8.8,
    childCount: 2,
    badges: ['杜比视界'],
    stereo: '2d',
    genres: ['科幻'],
  },
  'season-1': {
    id: 'season-1',
    kind: 'season',
    title: '第一季',
    subtitle: '共 2 集',
    poster: `${IMG}/posters/season-1.jpg`,
    parentId: 'series-1',
    index: 1,
    childCount: 2,
  },
  'episode-1': {
    id: 'episode-1',
    kind: 'episode',
    title: '第 1 集 · 启航',
    subtitle: 'S1E1 · 48 分钟',
    thumb: `${IMG}/thumbs/ep-1.jpg`,
    durationMs: 2_880_000,
    progressMs: 600_000,
    played: false,
    parentId: 'season-1',
    index: 1,
    stereo: 'ou',
  },
  'episode-2': {
    id: 'episode-2',
    kind: 'episode',
    title: '第 2 集 · 跃迁',
    subtitle: 'S1E2 · 52 分钟',
    thumb: `${IMG}/thumbs/ep-2.jpg`,
    durationMs: 3_120_000,
    played: true,
    parentId: 'season-1',
    index: 2,
    stereo: 'mvhevc',
  },
  'multipart-video': {
    id: 'multipart-video',
    kind: 'video',
    title: '空间计算架构详解（合集分P）',
    subtitle: '技术创作者 · 3 分P · 含弹幕与音画分离流',
    thumb: `${IMG}/thumbs/multipart.jpg`,
    poster: `${IMG}/posters/multipart.jpg`,
    year: 2026,
    overview: '演示多 P 选集、音视频分离 DASH + Referer、会员清晰度锁定、弹幕分段与过期地址刷新。',
    rating: 9.1,
    durationMs: 900_000,
    badges: ['4K', '分P', '会员'],
    stereo: '2d',
    childCount: 3,
    extra: [
      { label: '分区', value: '科技 · XR' },
      { label: '选集', value: '共 3 P' },
    ],
  },
  'multipart-p1': {
    id: 'multipart-p1',
    kind: 'episode',
    title: 'P1 · 光学与双目视差',
    subtitle: '分P 1 · 15:00',
    thumb: `${IMG}/thumbs/multipart-p1.jpg`,
    durationMs: 900_000,
    parentId: 'multipart-video',
    index: 1,
  },
  'multipart-p2': {
    id: 'multipart-p2',
    kind: 'episode',
    title: 'P2 · 实时深度估计管线',
    subtitle: '分P 2 · 18:00',
    thumb: `${IMG}/thumbs/multipart-p2.jpg`,
    durationMs: 1_080_000,
    parentId: 'multipart-video',
    index: 2,
  },
  'multipart-p3': {
    id: 'multipart-p3',
    kind: 'episode',
    title: 'P3 · OpenXR 合成优化',
    subtitle: '分P 3 · 12:00',
    thumb: `${IMG}/thumbs/multipart-p3.jpg`,
    durationMs: 720_000,
    parentId: 'multipart-video',
    index: 3,
  },
  'vr-180': {
    id: 'vr-180',
    kind: 'video',
    title: '冰岛极光 180° 立体实拍',
    subtitle: 'VR180 · 8K',
    thumb: `${IMG}/thumbs/vr180.jpg`,
    durationMs: 420_000,
    stereo: '180',
    badges: ['VR180'],
  },
  'vr-360': {
    id: 'vr-360',
    kind: 'video',
    title: '国际空间站 360° 全景漫游',
    subtitle: 'VR360 · 全景',
    thumb: `${IMG}/thumbs/vr360.jpg`,
    durationMs: 540_000,
    stereo: '360',
    badges: ['360°'],
  },
  'folder-demos': {
    id: 'folder-demos',
    kind: 'folder',
    title: '协议演示目录',
    subtitle: '4 个条目',
    childCount: 4,
  },
  'collection-scifi': {
    id: 'collection-scifi',
    kind: 'collection',
    title: '科幻精选合集',
    subtitle: '2 部作品',
    poster: `${IMG}/posters/collection.jpg`,
    childCount: 2,
  },
  'live-1': {
    id: 'live-1',
    kind: 'live',
    title: '天文台实时望远镜直播',
    subtitle: '实时流 · 1080P',
    thumb: `${IMG}/thumbs/live-1.jpg`,
    badges: ['直播中'],
    stereo: '2d',
  },
  'person-1': {
    id: 'person-1',
    kind: 'person',
    title: '克里斯托弗·诺兰',
    subtitle: '导演',
    poster: `${IMG}/people/nolan.jpg`,
  },
};

function throwIfErrorTrigger(ctx, id) {
  if (!id || !id.startsWith('err-')) return;
  const code = id.slice(4);
  if (code === 'Uncaught') {
    throw new TypeError('Simulated unexpected runtime error');
  }
  if (code === 'RateLimited') {
    throw new ctx.Error('RateLimited', '请求过于频繁，请稍后重试。', { retryAfterSec: 42 });
  }
  const messages = {
    NeedsAuth: '会话已失效，请重新登录。',
    AuthFailed: '账号或密码无效。',
    NeedsSetup: '请先在设置中填写必填参数。',
    Unavailable: '参考服务器暂时不可达。',
    NotFound: '指定的测试条目不存在。',
    Forbidden: '该内容仅限会员观看。',
    Unsupported: '该片源受 DRM 保护，暂不支持播放。',
    Internal: '模拟内部错误。',
  };
  throw new ctx.Error(code, messages[code] || '未知测试错误。');
}

async function withUserState(ctx, item) {
  const favs = (await ctx.storage.get('favorites')) || {};
  const played = (await ctx.storage.get('played')) || {};
  const copy = Object.assign({}, item);
  if (Object.prototype.hasOwnProperty.call(favs, item.id)) copy.favorite = Boolean(favs[item.id]);
  if (Object.prototype.hasOwnProperty.call(played, item.id)) copy.played = Boolean(played[item.id]);
  return copy;
}

async function mapWithUserState(ctx, items) {
  const favs = (await ctx.storage.get('favorites')) || {};
  const played = (await ctx.storage.get('played')) || {};
  return items.map((item) => {
    const copy = Object.assign({}, item);
    if (Object.prototype.hasOwnProperty.call(favs, item.id)) copy.favorite = Boolean(favs[item.id]);
    if (Object.prototype.hasOwnProperty.call(played, item.id)) copy.played = Boolean(played[item.id]);
    return copy;
  });
}

export default {
  async init(ctx) {
    const count = ((await ctx.storage.get('initCount')) || 0) + 1;
    await ctx.storage.set('initCount', count);
    ctx.log.info(`reference init #${count} for instance ${ctx.instanceId}`);
  },

  async dispose(ctx) {
    const count = ((await ctx.storage.get('disposeCount')) || 0) + 1;
    await ctx.storage.set('disposeCount', count);
  },

  async home(ctx) {
    return [
      {
        id: 'shelf-hero',
        title: '今日推荐',
        style: 'hero',
        items: await mapWithUserState(ctx, [CATALOG['movie-full']]),
      },
      {
        id: 'shelf-continue',
        title: '继续观看',
        style: 'continue',
        items: await mapWithUserState(ctx, [CATALOG['movie-full'], CATALOG['episode-1']]),
      },
      {
        id: 'shelf-movies',
        title: '电影与剧集',
        style: 'poster',
        more: 'cat-library',
        items: await mapWithUserState(ctx, [
          CATALOG['movie-full'],
          CATALOG['series-1'],
          CATALOG['collection-scifi'],
        ]),
      },
      {
        id: 'shelf-videos',
        title: '视频与直播',
        style: 'thumb',
        more: 'cat-videos',
        items: await mapWithUserState(ctx, [
          CATALOG['multipart-video'],
          CATALOG['vr-180'],
          CATALOG['vr-360'],
          CATALOG['live-1'],
        ]),
      },
      {
        id: 'shelf-categories',
        title: '目录与人物',
        style: 'category',
        items: await mapWithUserState(ctx, [CATALOG['folder-demos'], CATALOG['person-1']]),
      },
    ];
  },

  categories() {
    return [
      { id: 'cat-library', title: '影视库', icon: 'movie' },
      { id: 'cat-videos', title: '视频与分P', icon: 'video' },
      { id: 'cat-live', title: '直播频道', icon: 'live' },
    ];
  },

  async browse(ctx, { categoryId = '', cursor = null, sort = null, filters = null } = {}) {
    throwIfErrorTrigger(ctx, categoryId);

    if (categoryId === 'compat-forward') {
      // §11.2 forward-compatibility probe: null optional fields, unknown kind dropped, out-of-range numeric ignored
      return {
        items: [
          {
            id: 'compat-valid',
            kind: 'movie',
            title: '向前兼容电影',
            subtitle: null,
            overview: null,
            poster: null,
            year: 99999,
            rating: 120,
            stereo: 'future-hologram-9d',
          },
          {
            id: 'compat-unknown-kind',
            kind: 'future-audiobook',
            title: '未知类型应被丢弃',
          },
        ],
        next: null,
      };
    }

    if (categoryId === '') {
      return {
        items: await mapWithUserState(ctx, [
          CATALOG['folder-demos'],
          CATALOG['movie-full'],
          CATALOG['series-1'],
          CATALOG['multipart-video'],
        ]),
        next: null,
      };
    }

    let pool;
    if (categoryId === 'cat-live') {
      pool = [CATALOG['live-1']];
    } else if (categoryId === 'cat-videos') {
      pool = [CATALOG['multipart-video'], CATALOG['vr-180'], CATALOG['vr-360']];
    } else {
      pool = [
        CATALOG['movie-full'],
        CATALOG['series-1'],
        CATALOG['multipart-video'],
        CATALOG['vr-180'],
        CATALOG['vr-360'],
        CATALOG['live-1'],
      ];
    }

    if (filters && filters.stereo) {
      pool = pool.filter((it) => it.stereo === filters.stereo);
    }
    if (sort === 'title_asc') {
      pool = pool.slice().sort((a, b) => a.title.localeCompare(b.title));
    } else if (sort === 'rating_desc') {
      pool = pool.slice().sort((a, b) => (b.rating || 0) - (a.rating || 0));
    }

    const pageSize = Math.max(1, Number(ctx.config.pageSize) || 2);
    const offset = cursor ? Number(String(cursor).replace(/^offset:/, '')) || 0 : 0;
    const slice = await mapWithUserState(ctx, pool.slice(offset, offset + pageSize));
    const next = offset + pageSize < pool.length ? `offset:${offset + pageSize}` : null;
    return { items: slice, next };
  },

  async detail(ctx, { itemId }) {
    throwIfErrorTrigger(ctx, itemId);
    const base = CATALOG[itemId];
    if (!base) {
      throw new ctx.Error('NotFound', `未找到条目 ${itemId}`);
    }
    const item = await withUserState(ctx, base);
    if (itemId === 'movie-full') {
      item.related = await mapWithUserState(ctx, [CATALOG['series-1'], CATALOG['vr-180']]);
    } else if (itemId === 'series-1') {
      item.seasons = [
        Object.assign({}, CATALOG['season-1'], {
          episodes: await mapWithUserState(ctx, [CATALOG['episode-1'], CATALOG['episode-2']]),
        }),
      ];
    } else if (itemId === 'season-1') {
      item.episodes = await mapWithUserState(ctx, [CATALOG['episode-1'], CATALOG['episode-2']]);
    } else if (itemId === 'multipart-video') {
      item.episodes = await mapWithUserState(ctx, [
        CATALOG['multipart-p1'],
        CATALOG['multipart-p2'],
        CATALOG['multipart-p3'],
      ]);
    }
    return item;
  },

  async playback(ctx, { itemId, quality = null, audio = null }) {
    throwIfErrorTrigger(ctx, itemId);

    if (itemId === 'drm-item') {
      throw new ctx.Error('Unsupported', '该片源受 Widevine DRM 保护，v1 协议不支持播放。');
    }

    if (itemId === 'live-dash-invalid') {
      return {
        live: true,
        durationMs: 60_000,
        dash: {
          video: [
            {
              id: 'lv1',
              url: `${CDN}/live/v.m4s`,
              mime: 'video/mp4',
              codecs: 'avc1.640028',
              bandwidth: 2_000_000,
              initRange: '0-500',
              indexRange: '501-900',
            },
          ],
          audio: [],
        },
      };
    }

    // Track generation counter to demonstrate URL expiry refresh
    const genKey = `pb_gen_${itemId}`;
    const gen = ((await ctx.storage.get(genKey)) || 0) + 1;
    await ctx.storage.set(genKey, gen);

    // 1. Separated DASH stream + durationMs (§11.2) + Referer + Cookie + locked 4K quality + expiring URL
    if (itemId === 'multipart-video' || itemId === 'multipart-p1' || itemId === 'dash-separated') {
      if (quality === '4k-vip') {
        throw new ctx.Error('Forbidden', '4K 超清画质需要会员账号。');
      }
      const chosen = quality || '1080p';
      const cookieHeader = (await ctx.cookies.get('https://api.reference.invalid/')) || 'ref_anon=1';
      return {
        title: CATALOG[itemId]?.title || '音画分离测试视频',
        durationMs: CATALOG[itemId]?.durationMs || 900_000,
        dash: {
          video: [
            {
              id: 'v-1080-avc',
              url: `${CDN}/dash/${itemId}/v1080.m4s?token=v${gen}`,
              backupUrls: [
                `${CDN}/dash-b1/${itemId}/v1080.m4s?token=v${gen}`,
                `${CDN}/dash-b2/${itemId}/v1080.m4s?token=v${gen}`,
              ],
              mime: 'video/mp4',
              codecs: 'avc1.640028',
              bandwidth: 6_000_000,
              width: 1920,
              height: 1080,
              frameRate: 60,
              quality: '1080p',
              initRange: '0-820',
              indexRange: '821-2048',
            },
            {
              id: 'v-720-avc',
              url: `${CDN}/dash/${itemId}/v720.m4s?token=v${gen}`,
              backupUrls: [`${CDN}/dash-b1/${itemId}/v720.m4s?token=v${gen}`],
              mime: 'video/mp4',
              codecs: 'avc1.64001f',
              bandwidth: 3_000_000,
              width: 1280,
              height: 720,
              frameRate: 30,
              quality: '720p',
              initRange: '0-800',
              indexRange: '801-1900',
            },
          ],
          audio: [
            {
              id: audio || 'a-aac-192',
              url: `${CDN}/dash/${itemId}/a192.m4s?token=v${gen}`,
              backupUrls: [`${CDN}/dash-b1/${itemId}/a192.m4s?token=v${gen}`],
              mime: 'audio/mp4',
              codecs: 'mp4a.40.2',
              bandwidth: 192_000,
              initRange: '0-640',
              indexRange: '641-1400',
            },
          ],
        },
        headers: {
          Referer: 'https://api.reference.invalid/video/multipart',
          'User-Agent': 'depth3d-reference/1.0',
          Cookie: cookieHeader,
        },
        qualities: [
          { id: '4k-vip', label: '4K 超清', locked: '需要会员' },
          { id: '1080p', label: '1080P 高码率', selected: chosen === '1080p' },
          { id: '720p', label: '720P 流畅', selected: chosen === '720p' },
        ],
        audios: [
          { id: 'a-aac-192', label: '国语 AAC', lang: 'zh' },
          { id: 'a-dolby', label: '杜比全景声', lang: 'zh' },
        ],
        subtitles: [
          {
            id: 'sub-bcc',
            label: '中文（AI 字幕 JSON）',
            lang: 'zh-CN',
            url: `${CDN}/subs/${itemId}.json`,
            format: 'json',
            headers: { Referer: 'https://api.reference.invalid/' },
            default: true,
          },
        ],
        expiresAt: Date.now() + 120_000,
        next: itemId === 'multipart-p1' ? 'multipart-p2' : undefined,
        session: `sess-${itemId}-${gen}`,
      };
    }

    // 2. Separated progressive video+audio without initRange/indexRange (MergingMediaSource path)
    if (itemId === 'dash-progressive') {
      return {
        durationMs: 600_000,
        dash: {
          video: [
            {
              id: 'vp-1080',
              url: `${CDN}/prog/video.mp4`,
              backupUrls: [`${CDN}/prog-backup/video.mp4`],
              mime: 'video/mp4',
              codecs: 'avc1.640028',
              bandwidth: 5_000_000,
              width: 1920,
              height: 1080,
            },
          ],
          audio: [
            {
              id: 'ap-aac',
              url: `${CDN}/prog/audio.m4a`,
              backupUrls: [`${CDN}/prog-backup/audio.m4a`],
              mime: 'audio/mp4',
              codecs: 'mp4a.40.2',
              bandwidth: 192_000,
            },
          ],
        },
        headers: { Referer: 'https://api.reference.invalid/' },
      };
    }

    // 3. Inline MPD XML mode
    if (itemId === 'mpd-direct') {
      return {
        mpd: `<?xml version="1.0" encoding="UTF-8"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT60S"><Period><AdaptationSet mimeType="video/mp4"><Representation id="1" bandwidth="2000000" codecs="avc1.640028" width="1920" height="1080"><BaseURL>${CDN}/mpd/stream.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>`,
        headers: { Authorization: 'Bearer ref-inline-mpd' },
      };
    }

    // 4. Live stream
    if (itemId === 'live-1') {
      return {
        url: `${CDN}/live/observatory/master.m3u8`,
        mime: 'application/x-mpegURL',
        live: true,
        headers: { Referer: 'https://api.reference.invalid/live' },
      };
    }

    // 5. Default: Direct URL mode with all subtitle formats + stereo hint + startMs
    const item = CATALOG[itemId];
    if (!item) {
      throw new ctx.Error('NotFound', `未找到可播放条目 ${itemId}`);
    }
    return {
      url: `${CDN}/hls/${itemId}/${quality || 'auto'}.m3u8?gen=${gen}`,
      mime: 'application/x-mpegURL',
      headers: {
        Authorization: 'Bearer ref-demo-token',
        Referer: 'https://api.reference.invalid/',
      },
      qualities: [
        { id: 'auto', label: '自动', selected: !quality || quality === 'auto' },
        { id: '1080p', label: '1080P', selected: quality === '1080p' },
      ],
      subtitles: [
        { id: 's-srt', label: '简体中文 SRT', lang: 'zh-CN', url: `${CDN}/subs/${itemId}.zh.srt`, format: 'srt', default: true },
        { id: 's-vtt', label: 'English VTT', lang: 'en', url: `${CDN}/subs/${itemId}.en.vtt`, format: 'vtt' },
        { id: 's-ass', label: '特效字幕 ASS', lang: 'zh-CN', url: `${CDN}/subs/${itemId}.zh.ass`, format: 'ass' },
        { id: 's-json', label: '结构化字幕 JSON', lang: 'zh-CN', url: `${CDN}/subs/${itemId}.zh.json`, format: 'json' },
      ],
      audios: [
        { id: 'orig', label: '原声', lang: 'en' },
        { id: 'zh', label: '中文配音', lang: 'zh' },
      ],
      startMs: item.progressMs || 0,
      durationMs: item.durationMs || undefined,
      stereo: item.stereo || '2d',
      expiresAt: Date.now() + 3_600_000,
      next: itemId === 'episode-1' ? 'episode-2' : undefined,
      session: `sess-${itemId}-${gen}`,
    };
  },

  async search(ctx, { query = '', cursor = null, kind = null } = {}) {
    const q = String(query).trim().toLowerCase();
    const matches = Object.values(CATALOG).filter((it) => {
      if (kind && it.kind !== kind) return false;
      return !q || it.title.toLowerCase().includes(q) || (it.overview || '').toLowerCase().includes(q);
    });
    const offset = cursor ? Number(cursor) || 0 : 0;
    const limit = 4;
    const page = await mapWithUserState(ctx, matches.slice(offset, offset + limit));
    return {
      items: page,
      next: offset + limit < matches.length ? String(offset + limit) : null,
    };
  },

  suggest(ctx, { query = '' } = {}) {
    const q = String(query).trim().toLowerCase();
    return Object.values(CATALOG)
      .map((it) => it.title)
      .filter((t) => !q || t.toLowerCase().includes(q))
      .slice(0, 5);
  },

  async reportProgress(ctx, { itemId, positionMs, durationMs, event, session = null }) {
    await ctx.storage.set('lastProgress', { itemId, positionMs, durationMs, event, session });
  },

  async setFavorite(ctx, { itemId, favorite }) {
    const favs = (await ctx.storage.get('favorites')) || {};
    favs[itemId] = Boolean(favorite);
    await ctx.storage.set('favorites', favs);
  },

  async setPlayed(ctx, { itemId, played }) {
    const playedMap = (await ctx.storage.get('played')) || {};
    playedMap[itemId] = Boolean(played);
    await ctx.storage.set('played', playedMap);
  },

  filters() {
    return {
      sorts: [
        { value: 'default', label: '默认排序' },
        { value: 'rating_desc', label: '评分最高' },
        { value: 'title_asc', label: '按片名' },
      ],
      filters: [
        {
          key: 'stereo',
          label: '立体格式',
          options: [
            { value: 'sbs', label: '左右 3D (SBS)' },
            { value: 'ou', label: '上下 3D (OU)' },
            { value: 'mvhevc', label: '空间视频' },
            { value: '180', label: 'VR180' },
            { value: '360', label: '360° 全景' },
          ],
        },
      ],
    };
  },

  danmaku(ctx, { itemId, fromMs = 0, toMs = 60_000 }) {
    const all = [
      { timeMs: 1_500, text: '前排打卡！3D 效果很立体', color: '#FFFFFF', mode: 'scroll' },
      { timeMs: 12_000, text: '高能预警', color: 0xFF6699, mode: 'top' },
      { timeMs: 45_000, text: '这里的视差控制刚刚好', color: '#00E5FF', mode: 'bottom' },
      { timeMs: 75_000, text: '第二段弹幕（60s–120s）', color: '#FFD700', mode: 'scroll' },
      { timeMs: 130_000, text: '第三段弹幕（120s–180s）', color: 0x80FF80, mode: 'scroll' },
    ];
    return all.filter((d) => d.timeMs >= fromMs && d.timeMs < toMs);
  },

  // Form login flow
  async login(ctx) {
    if (ctx.config.password === 'wrong') {
      throw new ctx.Error('AuthFailed', '密码错误，请核对后再试。');
    }
    const name = ctx.config.username || '参考测试用户';
    await ctx.storage.set('authToken', `form-token-${name}`);
    await ctx.cookies.set('https://api.reference.invalid/', `ref_sess=form_${name}; Path=/; Secure`);
    return {
      ok: true,
      user: { name, avatar: `${IMG}/avatars/tester.png` },
    };
  },

  // QR and Device login start
  async loginStart(ctx) {
    const state = `state-${ctx.crypto.sha256(`${ctx.instanceId}-${Date.now()}`).slice(0, 12)}`;
    await ctx.storage.set(`poll_${state}`, 0);
    if (ctx.config.authFlow === 'device') {
      return {
        url: 'https://api.reference.invalid/activate',
        code: 'D3-8492',
        expiresInSec: 300,
        state,
      };
    }
    return {
      qr: `https://api.reference.invalid/qr/confirm?state=${state}`,
      hint: ctx.t('qr_hint'),
      expiresInSec: 180,
      state,
    };
  },

  // QR and Device login poll: pending -> scanned -> done (or expired)
  async loginPoll(ctx, { state }) {
    if (state === 'expired-state') {
      return { status: 'expired' };
    }
    const key = `poll_${state}`;
    const step = ((await ctx.storage.get(key)) || 0) + 1;
    await ctx.storage.set(key, step);
    if (step === 1) return { status: 'pending' };
    if (step === 2) return { status: 'scanned' };
    await ctx.storage.set('authToken', `qr-token-${state}`);
    await ctx.cookies.set('https://api.reference.invalid/', `ref_sess=qr_${state}; Path=/; Secure`);
    return {
      status: 'done',
      user: { name: '扫码用户', avatar: `${IMG}/avatars/qr.png` },
    };
  },

  async logout(ctx) {
    await ctx.storage.remove('authToken');
    await ctx.cookies.clear();
  },
};
