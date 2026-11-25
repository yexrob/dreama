"""搜索页（search.html）。规则和数值见 ux-spec.md §3.9。

Panel coordinates throughout (1280 x 720). The top bar (0-96) and the kind row
(112-172) stay put; results scroll in a clip region from y = 184. The system
keyboard is drawn outside the panel, in canvas coordinates, because it floats in
front of the window.
"""
from lib import ic, scene, card, cursor, panel_page

CSS = """
.sf{position:absolute;left:152px;top:32px;width:984px;height:64px;border-radius:32px;background:var(--fill);
  display:flex;align-items:center;gap:14px;padding:0 2px 0 24px}
.sf>.ic{color:var(--text-2)}
.sf .q{font-size:28px;font-weight:500;white-space:nowrap}
.sf .ph{font-size:28px;font-weight:400;color:var(--text-2);white-space:nowrap}
.sf .x{margin-left:auto;width:60px;height:60px;background:transparent}
.sf .x .ic{width:26px;height:26px;color:var(--text-2)}
.sf.edit{background:var(--sel);box-shadow:inset 0 0 0 2px rgba(255,255,255,.35)}
.sf.off{background:var(--surface)}
.sf.off .ph,.sf.off>.ic{color:var(--text-3)}
.caret{width:3px;height:34px;border-radius:2px;background:var(--text);margin-left:-12px;flex:none;animation:blink 1.06s steps(1) infinite}
@keyframes blink{50%{opacity:0}}
.chip{height:60px;padding:0 24px;border-radius:30px;background:var(--fill);display:inline-flex;align-items:center;gap:10px;
  font-size:24px;font-weight:500;white-space:nowrap;flex:none;transition:transform .12s var(--ease),background .12s}
.chip .ic{width:24px;height:24px;color:var(--text-2)}
.chip:hover,.chip.is-hover{background:var(--fill-hi);transform:scale(1.04)}
.chips{position:absolute;display:flex;gap:16px;overflow:hidden}
.seg span.off{color:var(--text-3)}
.stat{position:absolute;right:64px;top:112px;height:60px;display:flex;align-items:center;gap:12px;font-size:24px;font-weight:500;color:var(--text-2);white-space:nowrap}
.clip{position:absolute;left:0;top:184px;width:1280px;height:536px;overflow:hidden}
.clip>.in{position:absolute;left:0;top:-184px;width:1280px;height:1400px}
.rhd{position:absolute;left:48px;height:60px;padding:0 16px;border-radius:16px;display:flex;align-items:center;gap:12px;white-space:nowrap;transition:background .12s}
.rhd .n{font-size:28px;font-weight:600}
.rhd .s{font-size:24px;font-weight:500;color:var(--text-2)}
.rhd .ic{width:24px;height:24px;color:var(--text-2)}
.rhd:hover,.rhd.is-hover{background:var(--fill)}
.srow{position:absolute;left:64px;display:flex;gap:24px;align-items:flex-start}
.ri{flex:none;width:128px}
.ri.w{width:341px}
.ri .im{position:relative;height:192px;border-radius:14px;overflow:hidden;transition:transform .14s var(--ease),box-shadow .14s}
.ri .tt{margin-top:10px;font-size:24px;line-height:34px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ri .mt{font-size:24px;line-height:34px;font-weight:500;color:var(--text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ri:hover .im,.ri.is-hover .im{transform:scale(1.06);box-shadow:0 18px 44px rgba(0,0,0,.6)}
.ri.f .im{background:var(--surface-hi);display:grid;place-items:center}
.ri.f .im .ic{width:48px;height:48px;color:var(--text-2)}
.ri.more .im{background:var(--surface);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;font-size:24px;font-weight:500;color:var(--text-2)}
.ri.more .im b{font-size:28px;font-weight:600;color:var(--text)}
.ri .sk{height:192px;border-radius:14px}
.sline{position:absolute;left:40px;width:1200px;height:72px;padding:0 6px 0 24px;border-radius:16px;background:var(--surface);
  display:flex;align-items:center;gap:16px;font-size:28px;font-weight:500;white-space:nowrap}
.sline>.ic{color:var(--text-2)}
.sline .r{margin-left:auto}
.tail{position:absolute;left:64px;height:60px;display:flex;align-items:center;gap:14px;font-size:24px;font-weight:500;color:var(--text-2);white-space:nowrap}
.sg{display:flex;align-items:center;gap:20px;height:72px;padding:0 6px 0 24px;border-radius:16px;transition:background .12s}
.sg:hover,.sg.is-hover{background:var(--surface-hi)}
.sg .lead{width:40px;display:grid;place-items:center;flex:none}
.sg .lead .ic{color:var(--text-2)}
.sg .lab{font-size:28px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.sg .lab .c2{font-weight:400}
.sg .r{margin-left:auto;display:flex;align-items:center;gap:8px;font-size:24px;font-weight:500;color:var(--text-2);white-space:nowrap}
.sg .r .ic{width:24px;height:24px}
.sg .r .ibtn{width:60px;height:60px}
.sg .r .ibtn .ic{width:26px;height:26px}
.mini{position:relative;width:40px;height:60px;border-radius:6px;overflow:hidden}
.kb{position:absolute;left:300px;top:590px;width:1000px;height:340px;border-radius:28px;background:rgba(40,43,49,.97);
  box-shadow:0 30px 80px rgba(0,0,0,.45);padding:16px 20px;z-index:50}
.kb .cand{height:48px;display:flex;align-items:center;gap:40px;padding:0 16px;font-size:26px;color:#C9CBD0}
.kb .cand b{color:#fff;font-weight:600}
.kb .kr{display:flex;gap:8px;margin-top:8px;justify-content:center}
.kb .k{height:56px;width:88px;border-radius:12px;background:rgba(255,255,255,.12);display:grid;place-items:center;font-size:24px;font-weight:500;color:#ECECEE;flex:none}
.kb .k.m{width:128px;color:#C9CBD0}
.kb .k.go{width:184px;background:#ECECEE;color:#0E0F12;font-weight:600}
.kb .k.sp{width:304px}
.sa{position:absolute;left:160px;width:1280px;top:570px;height:0;border-top:2px dashed rgba(255,255,255,.55);z-index:55;pointer-events:none}
.empty{position:absolute;left:64px;right:64px;display:flex;flex-direction:column;align-items:center;text-align:center}
"""

# ------------------------------------------------------------------ pieces

def field(text=None, edit=False, off=False, hover_x=False, ph='搜索片名、剧集或视频'):
    cls = 'sf' + (' edit' if edit else '') + (' off' if off else '')
    if text:
        inner = f'<span class="q">{text}</span>' + ('<span class="caret"></span>' if edit else '')
        inner += f'<span class="ibtn x{" is-hover" if hover_x else ""}">{ic("close")}</span>'
    else:
        inner = ('<span class="caret" style="margin-left:0"></span>' if edit else '') + f'<span class="ph">{ph}</span>'
    return f'<div class="{cls}">{ic("search")}{inner}</div>'


def topbar(text=None, edit=False, off=False, mic=True, ph='搜索片名、剧集或视频'):
    back = f'<span class="ibtn bg back">{ic("chev-l")}</span>'
    m = f'<span class="ibtn bg" style="position:absolute;left:1152px;top:32px">{ic("mic")}</span>' if mic else ''
    return back + field(text, edit, off, ph=ph) + m


def kinds(sel='全部', off=(), hover=None):
    spans = ''.join(f'<span class="{"sel" if k == sel else ""}{" off" if k in off else ""}{" is-hover" if k == hover else ""}">{k}</span>'
                    for k in ('全部', '电影', '剧集', '视频'))
    return f'<div class="abs" style="left:64px;top:112px"><span class="seg">{spans}</span></div>'


def stat(text, busy=False):
    return f'<div class="stat">{"<span class=breath></span>" if busy else ""}{text}</div>'


def ri(kind, sc, title, meta, prog=None, filt='', hover=False, glyph=False):
    """kind: p = 2:3 poster, w = 16:9, f = folder. All images are 192 high."""
    h = ' is-hover' if hover else ''
    if kind == 'f':
        im = ic('folder')
    else:
        im = scene(sc, f'filter:{filt}' if filt else '')
        if prog is not None:
            im += f'<div class="prog"><b style="width:{prog}%"></b></div>'
        if glyph:
            im += f'<div class="playglyph">{ic("play")}</div>'
    return (f'<div class="ri {kind}{h}"><div class="im">{im}</div>'
            f'<div class="tt">{title}</div><div class="mt">{meta}</div></div>')


def more(n):
    return f'<div class="ri more"><div class="im"><b>全部</b>{n} 个{ic("chev-r", 24)}</div></div>'


def sk_item(w=128):
    return f'<div class="ri" style="width:{w}px"><div class="sk"></div></div>'


def src_row(y, name, sub, items, hover_head=False):
    return (f'<div class="rhd{" is-hover" if hover_head else ""}" style="top:{y}px"><span class="n">{name}</span>'
            f'<span class="s">{sub}</span>{ic("chev-r")}</div>'
            f'<div class="srow" style="top:{y + 64}px">{items}</div>')


def sline(y, icon, text, btn, danger=False, hover=False):
    i = f'<span class="cd" style="display:flex">{ic(icon)}</span>' if danger else ic(icon)
    return (f'<div class="sline" style="top:{y}px">{i}<span>{text}</span>'
            f'<span class="r"><span class="btn sm{" is-hover" if hover else ""}">{btn}</span></span></div>')


def tail(y, text, busy=False):
    return f'<div class="tail" style="top:{y}px">{"<span class=breath></span>" if busy else ""}{text}</div>'


def clip(inner, scroll=0):
    return f'<div class="clip"><div class="in" style="top:{-184 - scroll}px">{inner}</div></div>'


def keyboard():
    rows = ['qwertyuiop', 'asdfghjkl']
    k = lambda t, c='': f'<span class="k {c}">{t}</span>'
    out = ('<div class="kb" data-st="typing"><div class="cand"><b>汐</b><span>水</span><span>流</span><span>湿</span>'
           '<span>声</span><span>人</span><span>汐表</span></div>')
    for r in rows:
        out += '<div class="kr">' + ''.join(k(c) for c in r) + '</div>'
    out += '<div class="kr">' + k('⇧', 'm') + ''.join(k(c) for c in 'zxcvbnm') + k('⌫', 'm') + '</div>'
    out += ('<div class="kr">' + k('123', 'm') + k('中 / 英', 'm') + k('，') + k('空格', 'sp') + k('。') + k('搜索', 'go') + '</div></div>')
    out += ('<div class="sa" data-st="typing"></div>'
            '<div class="anno" data-st="typing" style="left:1450px;top:586px;width:136px">安全区下沿<br>面板 y = 440</div>'
            '<div class="anno" data-st="typing" style="left:20px;top:640px;width:262px">系统键盘（示意）<br>位置和遮挡范围<br>要在头显上实测</div>')
    return out


# ------------------------------------------------------------------ data
NAS = [
    ('p', 'tide', '潮汐表', '剧集 · 2023', None, ''),
    ('w', 'tide', '退潮之后', '潮汐表 第 2 季第 3 集', None, 'hue-rotate(10deg) brightness(.85)'),
    ('p', 'night', '暗潮', '电影 · 2019', None, ''),
    ('p', 'dusk', '潮声', '剩 64 分钟', 30, ''),
    ('p', 'salt', '观潮的人', '电影 · 2016', None, ''),
    ('w', 'tide', '涨潮时分', '潮汐表 第 1 季第 6 集', None, 'brightness(.7) saturate(.8)'),
    ('p', 'summer', '潮湿的季节', '电影 · 2022', None, ''),
]
VIDS = [
    ('w', 'tide', '看潮汐的一百种方法', '12:40 · 3 天前', None, 'hue-rotate(-20deg)'),
    ('w', 'harbor', '江口观潮 4K 实拍', '08:15 · 上周', None, ''),
    ('w', 'salt', '潮间带里的小螃蟹', '21:03 · 去年', None, 'saturate(1.4)'),
    ('w', 'dusk', '夜潮边露营', '35:50 · 2 个月前', None, ''),
]


def items(data, hover=None, glyph=False):
    return ''.join(ri(k, sc, t, m, p, f, hover == t, glyph and hover == t) for k, sc, t, m, p, f in data)


NAS_SUB = 'Jellyfin · 23 个'


# ------------------------------------------------------------------ states

def st_idle():
    hist = ['雾港', '潮汐表', '慢车 第二季', '海边 纪录片', '晚班', '冬至以后', '玻璃城']
    chips = ''.join(f'<span class="chip{" is-hover" if h == "雾港" else ""}">{ic("clock")}{h}</span>' for h in hist)
    seen = ''.join([
        card('harbor', '雾港', '剩 38 分钟', 66, meta_left=True),
        card('tide', '潮汐表', '第 2 季第 4 集', 56, meta_left=True, filt='hue-rotate(10deg) brightness(.85)'),
        card('night', '长夜将尽', '剩 65 分钟', 45, meta_left=True),
        card('train', '慢车', '已看完', None, meta_left=True),
        card('glass', '玻璃城', '剩 12 分钟', 90, meta_left=True),
    ])
    added = ''.join(ri('p', sc, t, m) for sc, t, m in (
        ('snow', '冬至以后', '电影 · NAS'), ('shift', '晚班', '剧集 · NAS'), ('rust', '无人区信号', '电影 · 书房 Mac'),
        ('echo', '回声测距', '电影 · NAS'), ('summer', '第七个夏天', '电影 · 本机'), ('glass', '玻璃城', '剧集 · NAS'),
        ('dusk', '远山灯火', '电影 · NAS'), ('salt', '盐湖', '电影 · NAS')))
    return ('<div data-st="idle">' + topbar() +
            '<div class="sec-title" style="top:120px">最近搜过</div>'
            '<div class="abs" style="right:64px;top:112px"><span class="btn ghost sm">清除</span></div>'
            f'<div class="chips" style="left:64px;top:180px;width:1152px">{chips}</div>'
            '<div class="sec-title" style="top:272px">最近看过</div>'
            f'<div class="shelf-row" style="top:326px">{seen}</div>'
            '<div class="sec-title" style="top:566px">最近添加</div>'
            f'<div class="srow" style="top:620px">{added}</div>'
            + cursor(118, 212) + '</div>')


def st_typing():
    sg = lambda lead, lab, right, h=False: (f'<div class="sg{" is-hover" if h else ""}"><span class="lead">{lead}</span>'
                                            f'<span class="lab">{lab}</span><span class="r">{right}</span></div>')
    fillin = f'<span class="ibtn">{ic("fill-in")}</span>'
    rows = (sg(ic('search'), '<span class="c2">搜索</span>「潮」', '') +
            sg(f'<span class="mini">{scene("tide")}</span>', '潮汐表', f'剧集 · NAS{ic("chev-r")}', h=True) +
            sg(ic('clock'), '潮汐表 第 3 季', fillin) +
            sg(ic('search'), '潮声', fillin))
    return ('<div data-st="typing">' + topbar('潮', edit=True) +
            f'<div class="abs list" style="left:40px;top:112px;width:1200px">{rows}</div>'
            + cursor(560, 226) + '</div>')


def st_streaming():
    return ('<div data-st="streaming">' + topbar('潮') + kinds(off=('视频',)) + stat('正在搜 3 个播放源', True) +
            clip(src_row(192, 'NAS', NAS_SUB, items(NAS, hover='潮汐表') + more(23)) +
                 tail(558, '还在搜：拾光视频、老片库', True))
            + cursor(130, 330) + '</div>')


def st_done():
    vids = items(VIDS)
    return ('<div data-st="done">' + topbar('潮') + kinds(hover='剧集') + stat('搜了 3 个播放源') +
            clip(src_row(192, 'NAS', NAS_SUB, items(NAS, hover='暗潮') + more(23)) +
                 src_row(558, '拾光视频', '插件 · 40 个以上', vids + more('40+')) +
                 src_row(924, '老片库', '插件 · 3 个',
                         ri('p', 'rust', '潮', '电影 · 1987') + ri('p', 'glass', '潮起潮落', '剧集 · 1994') +
                         ri('f', '', '潮系列合集', '文件夹 · 18 项')) +
                 tail(1290, '不支持搜索：本机、Google Drive、旧电脑共享'))
            + cursor(642, 344) + '</div>')


def st_expanded():
    # scrolled so the second and third lines of the grid are in view; page 2 is loading
    l1 = [('p', 'tide', '潮汐表', '剧集 · 2023', None, ''),
          ('w', 'tide', '退潮之后', '潮汐表 第 2 季第 3 集', None, 'hue-rotate(10deg) brightness(.85)'),
          ('p', 'night', '暗潮', '电影 · 2019', None, ''),
          ('p', 'dusk', '潮声', '剩 64 分钟', 30, ''),
          ('p', 'salt', '观潮的人', '电影 · 2016', None, '')]
    l2 = [('w', 'tide', '涨潮时分', '潮汐表 第 1 季第 6 集', None, 'brightness(.7) saturate(.8)'),
          ('p', 'summer', '潮湿的季节', '电影 · 2022', None, ''),
          ('p', 'harbor', '潮水退去', '电影 · 2020', None, 'hue-rotate(30deg)'),
          ('p', 'echo', '回潮', '剧集 · 2024', None, ''),
          ('p', 'shift', '潮夜', '电影 · 2018', None, '')]
    l3 = [('p', 'snow', '冬潮', '电影 · 2021', None, ''),
          ('w', 'tide', '大潮', '潮汐表 第 3 季第 1 集', 40, 'hue-rotate(-24deg) brightness(.9)')]
    line = lambda y, data, extra='', hover=None: f'<div class="srow" style="top:{y}px">{items(data, hover)}{extra}</div>'
    grid = (line(196, l1) + line(498, l2, hover='潮湿的季节') +
            line(800, l3, sk_item() + sk_item() + sk_item()) +
            tail(1100, '<span class=breath></span>正在加载第 2 页'))
    head = ('<div class="abs" style="left:152px;top:32px;display:flex;align-items:baseline;gap:20px;height:64px">'
            '<span class="t-title" style="line-height:64px">NAS 里的「潮」</span><span class="t-cap c2">Jellyfin · 23 个</span></div>')
    return ('<div data-st="expanded">' + f'<span class="ibtn bg back">{ic("chev-l")}</span>' + head +
            kinds() + clip(grid, scroll=302) + cursor(492, 300) + '</div>')


def st_noresult():
    sug = ''.join(f'<span class="chip{" is-hover" if s == "雾港" else ""}">{ic("search")}{s}</span>'
                  for s in ('雾港', '雾港 导演剪辑版', '雾中的港口'))
    return ('<div data-st="noresult">' + topbar('雾港二') + kinds(off=('电影', '剧集', '视频')) + stat('搜了 3 个播放源') +
            '<div class="empty" style="top:224px">'
            '<div class="t-title">没有找到「雾港二」</div>'
            '<div class="t-body c2" style="margin-top:10px">NAS、拾光视频、老片库里都没有。换个说法，或者少打几个字。</div>'
            '<div class="t-cap c2" style="margin-top:36px">是不是要找</div>'
            f'<div style="margin-top:14px;display:flex;gap:16px;justify-content:center">{sug}</div>'
            '<div style="margin-top:44px;display:flex;align-items:center;gap:16px">'
            '<span class="t-cap c2">本机、Google Drive、旧电脑共享不支持搜索</span>'
            f'<span class="btn ghost sm">去媒体库翻翻{ic("chev-r")}</span></div></div>'
            + cursor(520, 452) + '</div>')


def st_trouble():
    return ('<div data-st="trouble">' + topbar('潮') + kinds() + stat('搜了 3 个播放源') +
            clip(src_row(192, 'NAS', NAS_SUB, items(NAS) + more(23)) +
                 sline(550, 'user', '「拾光视频」登录后才能搜', '登录') +
                 sline(630, 'alert', '「老片库」连不上，这次没搜它', f'{ic("refresh")}重试', danger=True, hover=True) +
                 tail(718, '不支持搜索：本机、Google Drive、旧电脑共享'))
            + cursor(1178, 666) + '</div>')


def st_nosource():
    return ('<div data-st="nosource">' + topbar(off=True, mic=False, ph='没有可以搜索的播放源') +
            '<div class="empty" style="top:176px">'
            f'<div class="circ">{ic("search", 44, "c2")}</div>'
            '<div class="t-title" style="margin-top:28px">你的播放源都不支持搜索</div>'
            '<div class="t-body c2" style="margin-top:10px;max-width:820px">本机、Google Drive、旧电脑共享只能按文件夹翻。'
            '加一个 Jellyfin、Emby 或支持搜索的插件，这里就能搜。</div>'
            '<div class="btns" style="margin-top:40px;justify-content:center">'
            '<span class="btn primary is-hover">去媒体库</span><span class="btn">添加播放源</span></div></div>'
            + cursor(552, 560) + '</div>')


STATES = [
    ('idle', '未输入', '进来不弹键盘：大多数搜索是找回看过的片子，搜索记录和最近看过一点就到。键盘会挡住下半屏，等你点搜索框再弹。'),
    ('typing', '输入中', '键盘挡住下半屏，联想只放在上面的安全区，最多 4 行。第一行永远是原样搜索。本地已知的片名立即出现，播放源的联想只往后补，射线在列表里时不替换。'),
    ('streaming', '结果陆续到达', '按下「搜索」才去问播放源。300 ms 时先到的按播放源顺序排好，之后到的只往最下面加。还没到的写在末尾一行，不占整行骨架。'),
    ('done', '全部到达', '后到的拾光视频和老片库接在下面，上面的行一动不动。所有图片等高 192：海报 128 宽，横图 341 宽。类型行一直在，没有结果的类型变灰。'),
    ('expanded', '展开单个播放源', '点行标题或末尾的「全部 23 个」：这个源的全部结果，等高流式排列，只往后加。离底部 1.5 屏就取下一页，超过 300 ms 才出骨架。'),
    ('noresult', '无结果', '说清楚搜了哪几个源，给播放源返回的联想当「是不是要找」。不能搜的源也点名，并给一条去媒体库的路。'),
    ('trouble', '失败与需要登录', '出问题的源只占一行，不弹窗，不挡别的结果。需要登录给「登录」，连不上给「重试」。重试和登录成功后，结果在这一行原地展开。'),
    ('nosource', '没有可搜索的播放源', '只有 WebDAV、SMB、DLNA 和本机文件夹时，搜索框禁用，直接说为什么，给两条路。'),
]


def search_page():
    body = (st_idle() + st_typing() + st_streaming() + st_done() + st_expanded() + st_noresult()
            + st_trouble() + st_nosource())
    return panel_page('搜索', '跨所有能搜的播放源', STATES, body, CSS, outside=keyboard())
