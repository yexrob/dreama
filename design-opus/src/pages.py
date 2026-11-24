from lib import (ic, scene, poster, card, qr, nav, cursor, row, seg, chev, panel_page)

EXTRA = """
.opt{position:relative;background:var(--surface);border-radius:20px;padding:32px;transition:transform .14s var(--ease),background .14s}
.opt:hover,.opt.is-hover{background:var(--surface-hi);transform:scale(1.03)}
.opt.on{background:var(--sel)}
.opt .lead{width:40px;height:40px;color:var(--text-2)}
.opt .chk{position:absolute;right:28px;top:28px;width:40px;height:40px;border-radius:50%;box-shadow:inset 0 0 0 2.5px rgba(255,255,255,.35);display:grid;place-items:center}
.opt.on .chk{background:var(--text);box-shadow:none;color:#0E0F12}
.opt .chk .ic{width:24px;height:24px;stroke-width:3}
.vbtn{flex:1;height:96px;padding:0 22px;border-radius:16px;background:rgba(255,255,255,.08);display:flex;flex-direction:column;justify-content:center;min-width:0;transition:transform .12s var(--ease),background .12s}
.vbtn:hover,.vbtn.is-hover{background:var(--fill-hi);transform:scale(1.03)}
.vbtn .v{font-size:28px;line-height:1.3;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pbar{height:6px;border-radius:3px;background:rgba(255,255,255,.22);flex:none}
.pbar b{display:block;height:100%;border-radius:3px;background:var(--accent)}
.dots{position:absolute;left:0;right:0;top:44px;display:flex;justify-content:center;gap:10px}
.dots i{display:block;width:10px;height:10px;border-radius:5px;background:rgba(255,255,255,.28)}
.dots i.on{width:30px;background:var(--text)}
.pc{width:368px;height:96px;padding:0 24px;border-radius:var(--r);background:var(--surface);display:flex;align-items:center;gap:20px;transition:transform .14s var(--ease),background .14s}
.pc:hover,.pc.is-hover{background:var(--surface-hi);transform:scale(1.03)}
.pc .lead{width:36px;height:36px;color:var(--text-2)}
.typ{height:132px;border-radius:var(--r);background:var(--surface);display:flex;flex-direction:column;justify-content:center;align-items:center;gap:14px;font-size:24px;font-weight:500}
.typ:hover,.typ.is-hover{background:var(--surface-hi);transform:scale(1.04)}
.typ .ic{width:36px;height:36px;color:var(--text-2)}
.row.c{min-height:72px}
.circ{width:88px;height:88px;border-radius:50%;background:var(--fill);display:grid;place-items:center}
.circ .ic{width:44px;height:44px}
.step{display:flex;gap:18px;font-size:28px;line-height:1.5}
.step b{font-weight:600;color:var(--text-2);width:20px;flex:none}
.addr{display:inline-block;margin-top:20px;padding:14px 24px;border-radius:14px;background:var(--surface);font-size:32px;font-weight:600;letter-spacing:.02em}
"""


# ====================================================================== home
def home():
    hero = (f'<div class="abs" style="left:0;top:0;width:1280px;height:560px">{scene("harbor")}'
            '<div class="scrim-l"></div><div class="scrim-b"></div><div class="scrim-t"></div></div>')
    cards = ''.join([
        card('night', '长夜将尽', '剩 65 分钟', 45, meta_left=True),
        card('tide', '潮汐表', '第 4 集', 56, cls='is-hover', glyph=True, meta_left=True),
        card('train', '慢车', '剩 12 分钟', 88, meta_left=True),
        card('summer', '第七个夏天', '剩 80 分钟', 22, meta_left=True),
        card('echo', '回声测距', '剩 40 分钟', 60, meta_left=True),
    ])
    default = (
        '<div data-st="default">' + hero + nav('首页', solid=False) +
        '<div class="abs" style="left:64px;top:128px;width:640px">'
        '<div class="t-cap c2">上次看到 · 来自 NAS</div>'
        '<div class="t-display" style="margin-top:6px">雾港</div>'
        '<div class="t-cap c2" style="margin-top:12px">2025 · 剧情 · 剩余 38 分钟</div>'
        f'<div class="btns" style="margin-top:28px"><span class="btn primary">{ic("play")}继续播放</span>'
        '<span class="btn">详情</span></div></div>'
        '<div class="sec-title" style="top:424px">继续观看</div>'
        f'<div class="shelf-row" style="top:482px">{cards}</div>' + cursor(566, 600) + '</div>')

    row1 = ''.join(poster(k, 'is-hover' if k == 'summer' else '') for k in
                   ('glass', 'summer', 'dusk', 'shift', 'salt', 'rust', 'snow'))
    row2 = lambda dim: ''.join(poster(k, 'dim' if dim else '') for k in
                               ('harbor', 'night', 'train', 'echo', 'glass', 'dusk', 'salt'))
    scrolled = (
        '<div data-st="scrolled">' + nav('首页') +
        '<div class="sec-title" style="top:112px">最近添加</div>'
        f'<div class="shelf-row" style="top:166px">{row1}</div>'
        f'<div class="sec-title" style="top:462px">NAS · 电影{ic("chev-r")}</div>'
        f'<div class="shelf-row" style="top:516px">{row2(False)}</div>' + cursor(354, 300) + '</div>')
    error = (
        '<div data-st="error">' + nav('首页') +
        '<div class="sec-title" style="top:112px">最近添加</div>'
        f'<div class="shelf-row" style="top:166px">{row1.replace("is-hover", "")}</div>'
        f'<div class="sec-title" style="top:462px">NAS · 电影</div>'
        '<div class="abs" style="left:290px;top:452px;display:flex;align-items:center;gap:20px">'
        f'<span class="notice cd">{ic("alert")}连不上 NAS，下面是 2 小时前的内容</span>'
        f'<span class="btn sm is-hover">{ic("refresh")}重试</span></div>'
        f'<div class="shelf-row" style="top:532px">{row2(True)}</div>' + cursor(858, 488) + '</div>')

    tiles = [
        ('server', '局域网媒体库', '找到 NAS（Jellyfin）', True),
        ('headset', '头显里的视频', '需要允许访问存储', False),
        ('cast', '投屏', 'iPad、手机，不用设置', False),
    ]
    tiles_html = ''.join(
        f'<div class="opt{" is-hover" if h else ""}" style="width:352px;height:216px">{ic(i, cls="lead")}'
        f'<div class="t-head" style="margin-top:22px">{t}</div><div class="t-cap {"" if h else "c2"}" style="margin-top:4px">{s}</div></div>'
        for i, t, s, h in tiles)
    empty = (
        '<div data-st="empty">' + nav('首页') +
        '<div class="abs" style="left:64px;right:64px;top:148px;text-align:center">'
        '<div class="t-title">还没有可以看的片子</div>'
        '<div class="t-body c2" style="margin-top:12px">添加一个播放源，或者直接从 iPad、手机投屏过来。</div></div>'
        f'<div class="abs" style="left:88px;top:318px;display:flex;gap:24px">{tiles_html}</div>'
        '<div class="abs" style="left:0;right:0;top:584px;display:flex;justify-content:center">'
        f'<span class="btn ghost sm">片子在 Mac 或网盘里？在 Mac 上装 depth3d Host{ic("chev-r")}</span></div>'
        + cursor(392, 372) + '</div>')

    sk = lambda x, y, w, h, r=12: f'<div class="sk abs" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;border-radius:{r}px"></div>'
    loading = ('<div data-st="loading">' + nav('首页') +
               sk(64, 134, 220, 26) + sk(64, 178, 300, 62) + sk(64, 256, 340, 26) + sk(64, 312, 236, 64, 16)
               + sk(316, 312, 120, 64, 16) + sk(64, 430, 160, 32) +
               ''.join(sk(64 + i * 288, 482, 264, 149, 14) + sk(64 + i * 288, 647, 150, 26) for i in range(5)) + '</div>')

    states = [
        ('default', '默认', '首页第一屏：上次看的片子当主角，下面一行继续观看。悬停的卡片放大 6%，出现播放符号：点卡片直接续播。'),
        ('scrolled', '向下滚动', '滚动后英雄区收起，导航改为实底。每个播放源一行海报，行标题可点，进入该分类。'),
        ('empty', '没有播放源', '第一次进来、什么都没加时。局域网里已经找到的服务器排第一，点一下就能加；投屏不需要任何设置。'),
        ('loading', '加载中', '超过 300 ms 才出骨架。骨架与真实布局同尺寸，只做 1.6 秒的明暗呼吸，不做横扫高光。'),
        ('error', '一个源连不上', '只有出问题的那一行说话：显示缓存内容并压暗，旁边一个「重试」。其他行照常。'),
    ]
    return panel_page('首页播放墙', '面板 1280×720 · 1° ≈ 24 px', states,
                      default + scrolled + error + empty + loading, EXTRA)


# ====================================================================== library
SOURCES = [
    ('headset', '本机', '头显存储 · 23 个'),
    ('computer', '书房 Mac', 'depth3d Host · 片库和串流'),
    ('server', 'NAS', 'Jellyfin · 1,204 部'),
    ('cloud', 'Google Drive', 'WebDAV · 经书房 Mac'),
    ('folder', '旧电脑共享', None),
]


def source_list(sel, hover=None):
    out = ''
    for i, t, s in SOURCES:
        sub = s or '<span class="cd">SMB · 连不上</span>'
        cls = ('sel ' if t == sel else '') + ('is-hover' if t == hover else '')
        out += row(t, sub, i, cls=cls)
    return f'<div class="abs list" style="left:40px;top:120px;width:392px">{out}</div>'


def library():
    cats = [('电影', '312 部', 'glass'), ('剧集', '48 部', 'tide'), ('纪录片', '27 部', 'dusk'),
            ('动画', '16 部', 'summer'), ('最近添加', '近 30 天', 'harbor'), ('收藏', '9 部', 'night')]
    tiles = ''.join(
        f'<div class="tile{" is-hover" if n == "电影" else ""}" style="height:148px">{scene(sc)}<div class="shade"></div>'
        f'<div class="abs" style="left:20px;bottom:14px"><div class="t-head">{n}</div><div class="t-cap c2" style="color:#C9CBD0">{c}</div></div></div>'
        for n, c, sc in cats)
    default = ('<div data-st="default">' + nav('媒体库') + source_list('NAS') +
               f'<div class="abs" style="left:64px;top:620px"><span class="btn ghost sm">{ic("gear")}管理播放源</span></div>'
               '<div class="abs" style="left:488px;top:120px"><div class="t-title">NAS</div>'
               '<div class="t-cap c2" style="margin-top:4px">Jellyfin · 192.168.1.20</div></div>'
               f'<div class="abs" style="left:488px;top:240px;width:728px;display:grid;grid-template-columns:repeat(3,1fr);gap:24px">{tiles}</div>'
               + cursor(620, 300) + '</div>')

    keys = ['glass', 'harbor', 'night', 'summer', 'train', 'dusk', 'echo', 'shift', 'rust', 'salt', 'snow', 'glass',
            'night', 'train', 'summer', 'harbor', 'dusk', 'echo']
    grid = ''.join(poster(k, 'is-hover' if i == 1 else '', 'width:172px;height:258px') for i, k in enumerate(keys))
    gridst = ('<div data-st="grid">' +
              f'<span class="ibtn bg back">{ic("chev-l")}</span>'
              '<div class="abs" style="left:152px;top:32px;display:flex;align-items:baseline;gap:20px;height:64px;line-height:64px">'
              '<span class="t-title" style="line-height:64px">电影</span><span class="t-cap c2">NAS · 312 部</span></div>'
              f'<div class="abs" style="right:64px;top:34px"><span class="btn sm">最近添加{ic("chev-d")}</span></div>'
              f'<div class="abs" style="left:64px;top:128px;display:grid;grid-template-columns:repeat(6,172px);gap:26px 24px">{grid}</div>'
              + cursor(350, 240) + '</div>')

    offline = ('<div data-st="offline">' + nav('媒体库') + source_list('旧电脑共享') +
               f'<div class="abs" style="left:64px;top:620px"><span class="btn ghost sm">{ic("gear")}管理播放源</span></div>'
               f'<div class="abs" style="left:488px;top:196px;width:660px">{ic("alert", 48, "cd")}'
               '<div class="t-head" style="margin-top:20px">连不上「旧电脑共享」</div>'
               '<div class="t-body c2" style="margin-top:8px">这台电脑可能关机了，或者不在这个 Wi-Fi 里。</div>'
               '<div class="t-cap c2" style="margin-top:4px">上次连上：昨天 21:40</div>'
               f'<div class="btns" style="margin-top:36px"><span class="btn primary is-hover">{ic("refresh")}重试</span>'
               '<span class="btn">检查设置</span></div></div>' + cursor(636, 494) + '</div>')
    states = [
        ('default', '播放源与分类', '左边是所有播放源，右边是选中源的分类。悬停不切换右栏，必须点一下：射线扫过时内容不会乱跳。'),
        ('grid', '分类网格', '点进一个分类：6 列海报。海报本身带片名，所以网格下面不再重复写标题。右上角只给一个排序。'),
        ('offline', '源连不上', '说清楚发生了什么、可能的原因、上次成功的时间，再给一个主要动作。不显示错误代码。'),
    ]
    return panel_page('媒体库', '播放源 → 分类 → 海报网格', states, default + gridst + offline, EXTRA)


# ====================================================================== movie
def movie():
    bg = (f'<div class="abs" style="inset:0" data-st="default picker error">{scene("harbor")}'
          '<div class="scrim-l"></div><div class="scrim-b" style="height:50%"></div></div>'
          '<div class="abs" style="inset:0;background:#1A1D20" data-st="loading"></div>')
    vb = lambda lab, val, h=False: (f'<div class="vbtn{" is-hover" if h else ""}"><div class="t-cap c2">{lab}</div>'
                                    f'<div class="v">{val}</div></div>')
    values = (f'<div class="abs" style="left:64px;right:64px;top:584px;display:flex;gap:16px" data-st="default picker error">'
              + vb('3D', '自动转换 · 标准') + vb('字幕', '简体中文') + vb('音轨', 'English 5.1') + vb('版本', '4K HDR') + '</div>'
              '<div class="abs" style="left:64px;right:64px;top:584px;display:flex;gap:16px" data-st="loading">'
              + ''.join('<div class="sk" style="flex:1;height:96px;border-radius:16px"></div>' for _ in range(4)) + '</div>')
    text = ('<div class="abs" style="left:64px;top:140px;width:640px">'
            '<div class="t-display">雾港</div>'
            '<div class="t-cap c2" style="margin-top:14px">2025 · 剧情 · 1 小时 52 分 · 4K HDR</div>'
            '<div class="t-body" style="margin-top:14px;color:#C4C6CB">海港封雾七天，老引航员要把最后一艘船带进港。</div>'
            f'<div class="btns" style="margin-top:32px"><span class="btn primary" data-st="default picker error">{ic("play")}继续播放</span>'
            f'<span class="btn primary" data-st="loading">{ic("spinner", cls="spin")}正在准备</span>'
            f'<span class="btn">{ic("restart")}从头播放</span><span class="ibtn bg">{ic("heart")}</span></div>'
            '<div style="margin-top:22px;display:flex;align-items:center;gap:16px" data-st="default picker loading">'
            '<div class="pbar" style="width:200px"><b style="width:66%"></b></div><span class="t-cap c2">剩余 38 分钟</span></div>'
            '<div style="margin-top:18px;display:flex;align-items:center;gap:16px" data-st="error">'
            f'<span class="notice cd">{ic("alert")}NAS 没有响应，暂时不能播放</span>'
            f'<span class="btn sm is-hover">{ic("refresh")}重试</span></div>'
            '</div>')
    radio = lambda t, on=False, h=False: row(t, None, None, f'<span class="val" style="color:var(--text)">{ic("check", 28) if on else ""}</span>',
                                             cls=('c ' + ('is-hover' if h else '')))
    picker = ('<div data-st="picker"><div class="scrim"></div>'
              '<div class="sheet" style="left:300px;top:66px;width:680px">'
              f'<span class="ibtn bg" style="position:absolute;right:28px;top:28px">{ic("close")}</span>'
              '<div class="t-head">3D</div>'
              '<div class="t-cap c2" style="margin-top:4px">只对《雾港》生效。默认值在 设置 › 播放与 3D</div>'
              '<div class="list" style="margin:24px -24px 0">'
              + radio('自动转换（推荐）', True) + radio('原片是左右 3D') + radio('原片是上下 3D', h=True) +
              '</div><div style="margin-top:24px;display:flex;align-items:center;justify-content:space-between">'
              f'<span class="t-body" style="font-weight:500">强度</span>{seg(["关闭", "柔和", "标准", "强烈"], "标准")}</div></div>'
              + cursor(560, 400) + '</div>')
    states = [
        ('default', '默认', '一屏一个焦点：片名和「继续播放」。播放设置是第二层，放在底部、字小一级、底色更浅。'),
        ('picker', '3D 设置', '点「3D」弹出：片源三选一，强度四档（关闭就是看原始 2D）。改完立即生效并记在这部片子上，点外面或按返回键关闭。'),
        ('loading', '加载中', '文字先到（来自列表缓存），背景图后到、淡入 240 ms。点播放后按钮原地转圈，不跳页。'),
        ('error', '不能播放', '错误留在原地、说人话、给一个动作。不弹窗，不把用户推到别的页面。'),
    ]
    return panel_page('电影详情', '背景图是主角，界面退后', states, bg + f'<span class="ibtn bg back">{ic("chev-l")}</span>'
                      + text + values + picker + '<div data-st="error">' + cursor(562, 512) + '</div>', EXTRA)


# ====================================================================== series
def series():
    hues = ['hue-rotate(-14deg) brightness(.95)', 'hue-rotate(10deg) brightness(.85)', 'brightness(.7) saturate(.8)',
            'brightness(.55) hue-rotate(20deg)', 'hue-rotate(-30deg) saturate(.7) brightness(1.05)', 'brightness(.8)']
    # the row is scrolled so the next episode is in view, with one watched episode to its left
    eps = [('2', '灯塔', '已看完'), ('3', '退潮之后', '已看完'),
           ('4', '夜航', '剩 21 分钟'), ('5', '迷雾', '48 分钟'), ('6', '回港', '51 分钟')]
    cards = ''.join(card('tide', f'<span class="c2">{n}</span>&nbsp;&nbsp;{t}', m, 56 if n == '4' else None,
                         cls='is-hover' if n == '5' else '', glyph=n == '5', filt=hues[i + 1], meta_left=True)
                    for i, (n, t, m) in enumerate(eps))
    sk = ''.join(f'<div style="width:264px;flex:none"><div class="sk" style="height:149px;border-radius:14px"></div>'
                 f'<div class="sk" style="width:160px;height:26px;margin-top:16px"></div></div>' for _ in range(5))
    inner = (f'<div class="abs" style="left:0;top:0;width:1280px;height:480px">{scene("tide")}'
             '<div class="scrim-l"></div><div class="scrim-b" style="height:60%"></div></div>'
             f'<span class="ibtn bg back">{ic("chev-l")}</span>'
             '<div class="abs" style="left:64px;top:112px;width:800px">'
             '<div class="t-display">潮汐表</div>'
             '<div class="t-cap c2" style="margin-top:12px">剧集 · 3 季 · 2023–2025 · 来自 NAS</div>'
             '<div class="t-body" style="margin-top:12px;color:#C4C6CB">小镇气象员在一份旧潮汐表里，找到父亲留下的线索。</div>'
             f'<div class="btns" style="margin-top:26px"><span class="btn primary">{ic("play")}继续播放 · 第 2 季第 4 集</span>'
             f'<span class="ibtn bg">{ic("heart")}</span></div></div>'
             '<div class="abs" style="left:38px;top:404px;display:flex;gap:8px" data-st="default">'
             '<span class="tab">第 1 季</span><span class="tab sel">第 2 季</span><span class="tab">第 3 季</span></div>'
             '<div class="abs" style="left:38px;top:404px;display:flex;gap:8px" data-st="loading">'
             '<span class="tab">第 1 季</span><span class="tab">第 2 季</span><span class="tab sel">第 3 季</span></div>'
             f'<div class="shelf-row" style="top:488px" data-st="default">{cards}</div>'
             f'<div class="shelf-row" style="top:488px" data-st="loading">{sk}</div>'
             '<div data-st="default">' + cursor(1160, 600) + '</div>'
             '<div data-st="loading">' + cursor(470, 452) + '</div>')
    states = [
        ('default', '默认', '主按钮直接写明会播哪一集。选季用和导航同一种标签；集卡片：看完的只写「已看完」，看到一半的才有进度条。'),
        ('loading', '切换季', '切到第 3 季：标签立即变，集列表换成同尺寸骨架，页面其余部分不动。'),
    ]
    return panel_page('剧集详情', '选季 · 选集', states, inner, EXTRA)


# ====================================================================== sources
def sources():
    head = lambda title, btn: (f'<span class="ibtn bg back">{ic("chev-l")}</span>'
                               f'<div class="abs t-title" style="left:152px;top:32px;line-height:64px">{title}</div>'
                               + (f'<div class="abs" style="right:64px;top:32px">{btn}</div>' if btn else ''))
    tabs = lambda s: ('<div class="abs" style="left:38px;top:118px;display:flex;gap:8px">'
                      + ''.join(f'<span class="tab{" sel" if t == s else ""}">{t}</span>' for t in ('播放源', '插件')) + '</div>')
    rows = ''
    for i, t, s in SOURCES:
        right = f'<span class="val cd">{ic("alert")}连不上</span>' if s is None else ''
        rows += row(t, s or 'SMB · 192.168.1.8', i, right + chev(), cls='is-hover' if t == 'NAS' else '')
    default = ('<div data-st="default">' + head('播放源', f'<span class="btn primary">{ic("plus")}添加播放源</span>') + tabs('播放源') +
               f'<div class="abs list" style="left:40px;top:206px;width:1200px">{rows}</div>' + cursor(700, 430) + '</div>')

    plugs = (row('TMDB 刮削', '社区仓库 · 0.9.2 · 能访问 themoviedb.org', 'cube',
                 '<span class="btn sm is-hover">更新到 0.9.3</span>') +
             row('Jellyfin / Emby', '内置 · 1.2.0 · 能访问局域网', 'cube', chev()) +
             row('WebDAV', '内置 · 1.0.3 · 能访问局域网', 'cube', chev()) +
             row('SMB', '内置 · 1.0.1 · 能访问局域网', 'cube', chev()) +
             row('DLNA 媒体服务器', '内置 · 1.0.0 · 能访问局域网', 'cube', chev()))
    plugins = ('<div data-st="plugins">' + head('播放源', f'<span class="btn primary">{ic("plus")}添加插件仓库</span>') + tabs('插件') +
               f'<div class="abs list" style="left:40px;top:206px;width:1200px">{plugs}</div>' + cursor(1206, 268) + '</div>')

    found = (row('客厅盒子', 'DLNA 媒体服务器 · 192.168.1.42', 'server', '<span class="btn sm is-hover">添加</span>', cls='is-hover') +
             row('NAS', 'Jellyfin 10.9 · 192.168.1.20', 'server', f'<span class="val">{ic("check")}已添加</span>'))
    types = [('folder', '本机文件夹'), ('server', 'Jellyfin'), ('server', 'Emby'), ('cloud', 'WebDAV'),
             ('folder', 'SMB'), ('computer', 'depth3d Host')]
    tgrid = ''.join(f'<div class="typ">{ic(i)}<span>{t}</span></div>' for i, t in types)
    addbody = ('<div class="sec-title" style="top:126px">在局域网里找到</div>'
               f'<div class="abs list" style="left:40px;top:180px;width:1200px">{found}</div>'
               '<div class="sec-title" style="top:400px">手动添加</div>'
               f'<div class="abs" style="left:64px;top:452px;width:1152px;display:grid;grid-template-columns:repeat(6,1fr);gap:16px">{tgrid}</div>'
               '<div class="abs t-cap c2" style="left:64px;top:614px">没找到你的服务器？确认它和头显在同一个 Wi-Fi 里。</div>')
    add = '<div data-st="add">' + head('添加播放源', None) + addbody + cursor(1205, 248) + '</div>'
    phone = ('<div data-st="phone">' + head('添加播放源', None) + addbody.replace('is-hover', '') +
             '<div class="scrim"></div><div class="sheet" style="left:180px;top:96px;width:920px;display:flex;gap:48px">'
             f'<div class="qr">{qr(232)}</div><div style="flex:1">'
             '<div class="t-head">在手机上填写 Jellyfin</div>'
             '<div class="t-body c2" style="margin-top:8px">用手机相机扫这个码，在打开的网页里填地址和账号。填完，这里会自动继续。</div>'
             '<div class="notice c2" style="margin-top:24px"><span class="breath"></span>等待手机…</div>'
             '<div class="t-cap c2" style="margin-top:24px">扫不了码？用手机浏览器打开</div>'
             '<div class="t-body" style="font-weight:600;margin-top:2px">192.168.1.31:8080 · 验证码 4823</div>'
             '<div class="btns" style="margin-top:32px"><span class="btn is-hover">改用快速连接码</span><span class="btn ghost">取消</span></div>'
             '</div></div>' + cursor(792, 512) + '</div>')
    states = [
        ('default', '播放源', '正常的源不写状态，只有出问题的才出声。行高 88 px，整行可点，进入后能改地址、账号、在首页的顺序。'),
        ('plugins', '插件', '每个插件写明版本、来源和它能访问什么。有更新时行尾给一个按钮，没事就只有箭头。'),
        ('add', '添加播放源', '自动发现排在最前：Bonjour、SSDP、Jellyfin 的局域网广播。手动添加才需要输入，输入交给手机。'),
        ('phone', '用手机输入', '需要打字时不弹头显键盘：扫码在手机上填。Jellyfin 还可以用快速连接码，在已登录的设备上确认。'),
    ]
    return panel_page('播放源与插件', '管理页', states, default + plugins + add + phone, EXTRA)


# ====================================================================== cast
def cast():
    top = lambda notice, title, body, extra='': (
        f'<div class="abs" style="left:64px;top:136px;width:1152px"><div class="notice c2">{notice}</div>'
        f'<div class="t-display" style="margin-top:12px">{title}</div>'
        f'<div class="t-body c2" style="margin-top:12px">{body}</div>{extra}</div>')
    howto = ('<div class="abs" style="left:64px;top:372px;display:flex;gap:48px">'
             f'<div style="width:540px;display:flex;gap:20px">{ic("tablet", 40, "c2")}<div>'
             '<div class="t-head" style="font-size:28px">iPad / iPhone</div>'
             '<div class="t-body c2">控制中心 › 屏幕镜像 › 选这个名字</div></div></div>'
             f'<div style="width:540px;display:flex;gap:20px">{ic("phone", 40, "c2")}<div>'
             '<div class="t-head" style="font-size:28px">手机里的视频 App</div>'
             '<div class="t-body c2">播放时点「投屏」› 选这个名字</div></div></div></div>')
    pcs = ('<div class="sec-title" style="top:520px">电脑串流<span class="t-cap c2" style="margin-left:14px;font-weight:500">整个桌面，也能玩游戏</span></div>'
           '<div class="abs" style="left:64px;top:576px;display:flex;gap:24px">'
           f'<div class="pc is-hover">{ic("computer", cls="lead")}<div class="main" style="flex:1"><div class="t-body" style="font-weight:500;line-height:1.3">书房 Mac</div><div class="t-cap c2">已配对 · 点一下连接</div></div></div>'
           f'<div class="pc">{ic("computer", cls="lead")}<div class="main" style="flex:1"><div class="t-body" style="font-weight:500;line-height:1.3">游戏 PC</div><div class="t-cap c2">还没配对</div></div></div>'
           f'<div class="pc">{ic("plus", cls="lead")}<div class="main" style="flex:1"><div class="t-body" style="font-weight:500;line-height:1.3">添加电脑</div><div class="t-cap c2">用手机输入地址</div></div></div>'
           '</div>')
    waiting = top('<span class="breath"></span>正在等待投屏', '客厅的 depth3d', '在同一个 Wi-Fi 里，从 iPad、iPhone 或手机 App 选择这个名字。')
    default = '<div data-st="default">' + nav('投屏') + waiting + howto + pcs + cursor(400, 640) + '</div>'
    confirm = ('<div data-st="confirm">' + nav('投屏') + waiting + howto + pcs.replace('is-hover', '') +
               '<div class="scrim"></div><div class="sheet" style="left:340px;top:132px;width:600px">'
               f'{ic("phone", 44)}<div class="t-head" style="margin-top:20px">小林的 iPhone 想投屏到这里</div>'
               '<div class="t-body c2" style="margin-top:6px">第一次连接，需要你确认一下。</div>'
               '<div style="margin:24px -24px 0">' + row('以后自动允许这台设备', None, None, '<span class="tog on"></span>') + '</div>'
               '<div class="btns" style="margin-top:24px"><span class="btn primary is-hover">允许</span><span class="btn">拒绝</span></div>'
               '</div>' + cursor(496, 520) + '</div>')
    center = lambda icon, title, body, btns: (
        '<div class="abs" style="left:0;right:0;top:214px;display:flex;flex-direction:column;align-items:center;text-align:center">'
        f'<div class="circ">{icon}</div><div class="t-title" style="margin-top:28px">{title}</div>'
        f'<div class="t-body c2" style="margin-top:10px">{body}</div>'
        f'<div class="btns" style="margin-top:40px;justify-content:center">{btns}</div></div>')
    connecting = ('<div data-st="connecting">' + nav('投屏') +
                  center(ic("spinner", 40, "spin"), '正在连接小林的 iPad', '画面一出来就会自动进入 3D 播放。',
                         '<span class="btn">取消</span>') + '</div>')
    pin = ('<div data-st="pin">' + nav('投屏') + waiting + howto + pcs.replace('is-hover', '') +
           '<div class="scrim"></div><div class="sheet" style="left:260px;top:84px;width:760px">'
           '<div class="t-head">在电脑上输入配对码</div>'
           '<div class="t-body c2" style="margin-top:6px">在「游戏 PC」的 Sunshine 页面里输入：</div>'
           '<div class="t-display" style="font-size:88px;letter-spacing:.32em;margin-top:24px">3917</div>'
           '<div class="notice c2" style="margin-top:20px"><span class="breath"></span>等电脑确认…</div>'
           '<div class="t-cap c2" style="margin-top:16px">装了 depth3d Host 的 Mac 会自动配对。</div>'
           '<div class="btns" style="margin-top:28px"><span class="btn">取消</span></div></div></div>')
    nowifi = ('<div data-st="nowifi">' + nav('投屏') +
              center(f'<span class="cd">{ic("wifi", 40)}</span>', '还没连上 Wi-Fi',
                     '投屏和串流需要头显和 iPad、手机、电脑在同一个 Wi-Fi 里。',
                     '<span class="btn primary is-hover">打开 Wi-Fi 设置</span>')
              + cursor(766, 522) + '</div>')
    states = [
        ('default', '等待投屏', '这一页的主角是设备名：别的设备上要找的就是这几个字。iPad 和手机的做法各一句话。电脑串流在下面，要主动去连。'),
        ('confirm', '首次确认', '陌生设备第一次投屏要确认，之后自动允许（可在设置里撤销）。在任何页面都会弹出，不只在这一页。'),
        ('connecting', '连接中', '连上后直接进入 3D 播放，不需要再点。'),
        ('pin', '电脑配对', 'Sunshine 要 PIN：数字放到 88 px，方便对着抄。装了 Host 的 Mac 自动配对。'),
        ('nowifi', '没有网络', '说清楚为什么不能投屏，只给一个动作。'),
    ]
    return panel_page('投屏', '等待 iPad / 手机 / 电脑', states, default + confirm + connecting + pin + nowifi, EXTRA)


# ====================================================================== onboarding
def onboarding():
    dots = lambda n: '<div class="dots">' + ''.join(f'<i class="{"on" if i == n else ""}"></i>' for i in range(3)) + '</div>'
    skip = '<div class="abs" style="right:40px;top:24px"><span class="btn ghost sm">跳过</span></div>'
    planes = ('<div class="abs" style="left:460px;top:112px;width:360px;height:220px">'
              '<div class="abs" style="left:0;top:0;width:280px;height:158px;border-radius:14px;background:#22262b"></div>'
              '<div class="abs" style="left:40px;top:28px;width:280px;height:158px;border-radius:14px;overflow:hidden;opacity:.75">'
              + scene('harbor', 'filter:brightness(.6)') + '</div>'
              '<div class="abs" style="left:80px;top:56px;width:280px;height:158px;border-radius:14px;overflow:hidden">'
              + scene('harbor') + '</div></div>')
    welcome = ('<div data-st="welcome">' + dots(0) + planes +
               '<div class="abs" style="left:0;right:0;top:364px;text-align:center">'
               '<div class="t-display">depth3d</div>'
               '<div class="t-head c2" style="margin-top:10px;font-weight:500">任何画面，实时变成 3D</div>'
               '<div class="btns" style="justify-content:center;margin-top:48px"><span class="btn primary is-hover" style="min-width:240px">开始</span></div>'
               '</div>' + cursor(740, 560) + '</div>')
    opts = [('server', '局域网里的媒体库', '找到 NAS（Jellyfin）', True, False),
            ('headset', '头显里的视频', '需要允许访问存储', False, False),
            ('computer', 'Mac 上的片子', '装 depth3d Host 就能看', False, True)]
    oh = ''.join(f'<div class="opt{" on" if on else ""}{" is-hover" if h else ""}" style="width:368px;height:232px">'
                 f'{ic(i, cls="lead")}<span class="chk">{ic("check") if on else ""}</span>'
                 f'<div class="t-head" style="margin-top:26px">{t}</div><div class="t-cap {"" if on else "c2"}" style="margin-top:4px">{s}</div></div>'
                 for i, t, s, on, h in opts)
    srcs = ('<div data-st="sources">' + dots(1) + skip +
            '<div class="abs" style="left:64px;right:64px;top:104px;text-align:center">'
            '<div class="t-title">你的片子在哪里？</div>'
            '<div class="t-body c2" style="margin-top:10px">可以选几个，之后也能在「媒体库」里添加。</div></div>'
            f'<div class="abs" style="left:64px;top:262px;display:flex;gap:24px">{oh}</div>'
            '<div class="abs t-cap c2" style="left:0;right:0;top:530px;text-align:center">只想投屏？直接继续，不需要设置。</div>'
            '<div class="abs" style="left:0;right:0;top:590px;display:flex;justify-content:center"><span class="btn primary" style="min-width:240px">继续</span></div>'
            + cursor(1180, 470) + '</div>')
    laptop = ('<div class="abs" style="left:844px;top:206px;width:340px;height:240px">'
              '<div class="abs" style="left:20px;top:0;width:300px;height:196px;border-radius:14px;background:#22262b;box-shadow:inset 0 0 0 3px #3a3e45">'
              '<div class="abs" style="left:12px;right:12px;top:12px;height:20px;border-radius:5px;background:#30343a"></div>'
              '<div class="abs" style="right:22px;top:15px;width:14px;height:14px;border-radius:50%;background:var(--text)"></div></div>'
              '<div class="abs" style="left:0;top:200px;width:340px;height:14px;border-radius:0 0 10px 10px;background:#3a3e45"></div></div>')
    mac = ('<div data-st="mac">' + dots(1) + f'<span class="ibtn bg back">{ic("chev-l")}</span>' +
           '<div class="abs" style="left:64px;top:128px;width:720px">'
           '<div class="t-title">在 Mac 上装 depth3d Host</div>'
           '<div class="t-body c2" style="margin-top:12px">用 Mac 的浏览器打开，下载后拖进「应用程序」：</div>'
           '<div class="addr">depth3d.app/mac</div>'
           '<div style="margin-top:36px;display:flex;flex-direction:column;gap:10px">'
           '<div class="step"><b>1</b>打开它，菜单栏里会出现一个图标</div>'
           '<div class="step"><b>2</b>按提示允许「屏幕录制」，串流才有画面和声音</div>'
           '<div class="step"><b>3</b>回到这里，头显会自己找到 Mac</div></div>'
           '<div class="notice c2" style="margin-top:32px"><span class="breath"></span>正在局域网里找 Mac…</div></div>'
           + laptop + '<div class="abs" style="right:64px;bottom:48px"><span class="btn">以后再说</span></div></div>')
    ctl = [('tap', '扳机 / 捏合', '暂停、继续，点按钮'), ('stick', '摇杆左右', '快退、快进 10 秒'), ('move', '按住握把', '拖动、缩放屏幕')]
    ch = ''.join(f'<div class="opt" style="width:368px;height:236px;text-align:center"><div class="circ" style="margin:0 auto">{ic(i)}</div>'
                 f'<div class="t-head" style="margin-top:22px">{t}</div><div class="t-cap c2" style="margin-top:4px">{s}</div></div>'
                 for i, t, s in ctl)
    controls = ('<div data-st="controls">' + dots(2) +
                '<div class="abs" style="left:64px;right:64px;top:104px;text-align:center">'
                '<div class="t-title">看片时这样操作</div>'
                '<div class="t-body c2" style="margin-top:10px">指向画面，控制条就会出现。不指的时候，它会自己隐藏。</div></div>'
                f'<div class="abs" style="left:64px;top:262px;display:flex;gap:24px">{ch}</div>'
                '<div class="abs" style="left:0;right:0;top:590px;display:flex;justify-content:center"><span class="btn primary is-hover" style="min-width:240px">开始看片</span></div>'
                + cursor(748, 618) + '</div>')
    states = [
        ('welcome', '1 欢迎', '只有名字、一句话、一个按钮。插图用三层错开的画面暗示「深度」，不放产品截图。'),
        ('sources', '2 片源', '三个选项可以多选。局域网里已经发现的服务器预先勾上。只想投屏的人什么都不用选。'),
        ('mac', '2b 装 Host', '选了 Mac 才出现。地址短到可以照着敲；装好后头显自动发现，不需要任何输入。'),
        ('controls', '3 操作', '只教三件事，教完就开始看片。不做长教程，其他操作在播放时随用随现。'),
    ]
    return panel_page('首次使用引导', '三步，可跳过', states, welcome + srcs + mac + controls, EXTRA, default='sources')


# ====================================================================== settings
def settings():
    cats = ['播放与 3D', '屏幕与环境', '投屏', '手柄与手势', '播放源', '隐私', '关于']
    side = lambda s: ('<div class="abs list" style="left:40px;top:120px;width:344px">'
                      + ''.join(row(c, None, None, '', cls='c ' + ('sel' if c == s else '')) for c in cats) + '</div>')
    pane = lambda title, rows: (f'<div class="abs t-head" style="left:456px;top:124px">{title}</div>'
                                f'<div class="abs list" style="left:432px;top:186px;width:808px">{rows}</div>')
    play = (row('默认 3D 强度', '每部片子还能单独调', None, seg(['柔和', '标准', '强烈'], '标准', '强烈'), cls='is-hover') +
            row('识别原生 3D 片源', '左右、上下格式直接按 3D 播放', None, '<span class="tog on"></span>') +
            row('深度计算', '画质优先更耗电、更热', None, seg(['流畅优先', '画质优先'], '流畅优先')) +
            row('字幕位置', '放在画面前面，和 3D 深度一致', None, f'<span class="val">跟随画面{ic("chev-r")}</span>') +
            row('片尾自动播下一集', None, None, '<span class="tog on"></span>'))
    screen = (row('默认屏幕大小', '播放时随时能改', None, seg(['小', '中', '大', '巨幕'], '中')) +
              row('默认距离', None, None, seg(['近', '中', '远'], '中')) +
              row('播放时调暗房间', '像影院熄灯，暂停时亮回来', None, '<span class="tog on"></span>', cls='is-hover') +
              row('曲面屏幕', '屏幕很大时，边缘看得更清楚', None, '<span class="tog"></span>') +
              row('默认环境', None, None, f'<span class="val">透视{ic("chev-r")}</span>'))
    about = (row('版本', None, None, '<span class="val">0.1.0 · 2026-11-24</span>') +
             row('开源许可', '以 GPLv3 发布，源代码公开', None, chev()) +
             row('第三方组件', 'MoonlightXR、Sunshine、UxPlay、ZipDepth 等', None, chev()) +
             row('崩溃报告', '开启后，出错时把日志发给开发者', None, '<span class="tog"></span>', cls='is-hover') +
             row('检查更新', None, None, '<span class="val">已是最新</span>'))
    body = ('<div data-st="default">' + nav(None, gear=True) + side('播放与 3D') + pane('播放与 3D', play) + cursor(1202, 248) + '</div>'
            '<div data-st="screen">' + nav(None, gear=True) + side('屏幕与环境') + pane('屏幕与环境', screen) + cursor(940, 440) + '</div>'
            '<div data-st="about">' + nav(None, gear=True) + side('关于') + pane('关于', about) + cursor(940, 530) + '</div>')
    states = [
        ('default', '播放与 3D', '左栏分类，右栏选项。每行 88 px，整行可点；选项少于 5 个时直接平铺成分段选择，不用下拉。'),
        ('screen', '屏幕与环境', '这里是默认值。播放中在快捷菜单里改的只影响当前这部片子。'),
        ('about', '关于', '开源许可和第三方组件写清楚。崩溃报告默认关闭，要用户自己打开。'),
    ]
    return panel_page('设置', '两栏', states, body, EXTRA)
