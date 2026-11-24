"""Shared pieces for the design-opus mockups: icons, placeholder scenes, the room,
and the page frame. Every generated HTML file inlines all of it, so each mockup
stays self-contained and loads nothing from the network."""
import math
import os
import random

HERE = os.path.dirname(os.path.abspath(__file__))
BASE_CSS = open(os.path.join(HERE, 'base.css'), encoding='utf-8').read()


# ---------------------------------------------------------------- icons
def _gear():
    pts = []
    for i in range(8):
        a = i * 45
        for r, d in ((7.3, -17), (9.6, -9), (9.6, 9), (7.3, 17)):
            t = math.radians(a + d)
            pts.append(f'{12 + r * math.cos(t):.2f} {12 + r * math.sin(t):.2f}')
    return f'<path d="M{" L".join(pts)} Z"/><circle cx="12" cy="12" r="3.1"/>'


ICONS = {
    'play': '<path d="M8 5.6v12.8c0 .9 1 1.4 1.7.9l9.6-6.4a1.1 1.1 0 0 0 0-1.8L9.7 4.7C9 4.2 8 4.7 8 5.6z" fill="currentColor" stroke="none"/>',
    'pause': '<rect x="6.3" y="5" width="4" height="14" rx="1.3" fill="currentColor" stroke="none"/><rect x="13.7" y="5" width="4" height="14" rx="1.3" fill="currentColor" stroke="none"/>',
    'search': '<circle cx="10.5" cy="10.5" r="6.2"/><path d="M15.3 15.3 20 20"/>',
    'gear': _gear(),
    'chev-l': '<path d="M14.5 5.5 8 12l6.5 6.5"/>',
    'chev-r': '<path d="M9.5 5.5 16 12l-6.5 6.5"/>',
    'chev-d': '<path d="M6 9.5l6 6 6-6"/>',
    'plus': '<path d="M12 5v14M5 12h14"/>',
    'check': '<path d="M5 12.5l4.6 4.6L19 7.5"/>',
    'close': '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
    'sliders': '<path d="M4 7h8.5M17.5 7H20M4 17h2.5M11.5 17H20"/><circle cx="15" cy="7" r="2.4"/><circle cx="9" cy="17" r="2.4"/>',
    'heart': '<path d="M12 19.3s-7.4-4.5-7.4-9.9A4.1 4.1 0 0 1 12 7.1a4.1 4.1 0 0 1 7.4 2.3c0 5.4-7.4 9.9-7.4 9.9z"/>',
    'restart': '<path d="M5 12a7 7 0 1 0 2.1-5"/><path d="M5 4.8V9h4.2"/>',
    'cast': '<path d="M9 4.5h10a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-5"/><rect x="3" y="10" width="7" height="10.5" rx="1.8"/>',
    'computer': '<rect x="3" y="4.5" width="18" height="12" rx="2"/><path d="M9 20h6M12 16.5V20"/>',
    'tablet': '<rect x="5" y="3" width="14" height="18" rx="2.5"/><path d="M10.8 18h2.4"/>',
    'phone': '<rect x="7" y="2.8" width="10" height="18.4" rx="2.4"/><path d="M10.8 18.2h2.4"/>',
    'server': '<rect x="4" y="4" width="16" height="7" rx="1.8"/><rect x="4" y="13" width="16" height="7" rx="1.8"/><path d="M8 7.5h.01M8 16.5h.01"/>',
    'cloud': '<path d="M7.6 18.5h9.6a3.9 3.9 0 0 0 .5-7.8 5.5 5.5 0 0 0-10.6 1.5 3.2 3.2 0 0 0 .5 6.3z"/>',
    'folder': '<path d="M3.5 7.3a1.9 1.9 0 0 1 1.9-1.9h3.9l2 2.1h7.3a1.9 1.9 0 0 1 1.9 1.9v7.9a1.9 1.9 0 0 1-1.9 1.9H5.4a1.9 1.9 0 0 1-1.9-1.9z"/>',
    'headset': '<path d="M3.5 9.6A2.6 2.6 0 0 1 6.1 7h11.8a2.6 2.6 0 0 1 2.6 2.6v4.8a2.6 2.6 0 0 1-2.6 2.6h-2.7l-1.9-2.2a1.7 1.7 0 0 0-2.6 0L8.8 17H6.1a2.6 2.6 0 0 1-2.6-2.6z"/>',
    'refresh': '<path d="M19 12a7 7 0 1 1-2.1-5"/><path d="M19 4.8V9h-4.2"/>',
    'alert': '<circle cx="12" cy="12" r="8.6"/><path d="M12 7.6v5.2M12 16.3h.01"/>',
    'cube': '<path d="M12 3.6l7.4 4v8.8L12 20.4l-7.4-4V7.6z"/><path d="M4.6 7.6 12 11.6l7.4-4M12 11.6v8.8"/>',
    'screen': '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 10V8.5h1.6M17 10V8.5h-1.6M7 14v1.5h1.6M17 14v1.5h-1.6"/>',
    'subtitles': '<rect x="3" y="5" width="18" height="14" rx="2.4"/><path d="M7 12.5h3.5M13 12.5h4M7 15.5h7"/>',
    'audio': '<path d="M4.5 9.5h3l4.5-4v13l-4.5-4h-3z"/><path d="M15.5 9.2a4 4 0 0 1 0 5.6M18 6.7a7.6 7.6 0 0 1 0 10.6"/>',
    'env': '<path d="M3 18.5 9 10.5l3.6 4.8 2.4-3 6 6.2z"/><circle cx="16.5" cy="6.8" r="1.8"/>',
    'move': '<path d="M12 4v16M4 12h16M9.2 6.8 12 4l2.8 2.8M9.2 17.2 12 20l2.8-2.8M6.8 9.2 4 12l2.8 2.8M17.2 9.2 20 12l-2.8 2.8"/>',
    'tap': '<circle cx="12" cy="12" r="8.6"/><circle cx="12" cy="12" r="3.4" fill="currentColor" stroke="none"/>',
    'stick': '<circle cx="12" cy="12" r="4.2"/><path d="M5.6 9 2.8 12l2.8 3M18.4 9l2.8 3-2.8 3"/>',
    'wifi': '<path d="M3.5 9.6a12 12 0 0 1 17 0M6.6 12.9a7.6 7.6 0 0 1 10.8 0M9.6 16.1a3.3 3.3 0 0 1 4.8 0"/><path d="M12 19.4h.01"/>',
    'spinner': '<path d="M12 3.5a8.5 8.5 0 1 1-8.5 8.5"/>',
    'dim': '<circle cx="12" cy="12" r="8"/><path d="M12 4a8 8 0 0 1 0 16z" fill="currentColor" stroke="none"/>',
}


def icon_sprite():
    syms = ''.join(f'<symbol id="i-{k}" viewBox="0 0 24 24">{v}</symbol>' for k, v in ICONS.items())
    return f'<svg width="0" height="0" style="position:absolute" aria-hidden="true">{syms}</svg>'


def ic(name, size=None, cls='', style=''):
    st = f'width:{size}px;height:{size}px;' if size else ''
    return f'<svg class="ic {cls}" style="{st}{style}"><use href="#i-{name}"/></svg>'


# ---------------------------------------------------------------- scenes
def _bl(items, cls):
    return ''.join(f'<i class="{cls}" style="left:{l}%;width:{w}%;height:{h}%"></i>' for l, w, h in items)


SCENES = {
    'harbor': '<i class="fog"></i><i class="pier"></i><i class="post"></i><i class="lamp"></i><i class="ref"></i>'
              '<i class="crane"></i><i class="arm"></i><i class="cab"></i>',
    'tide': '<i class="sun"></i><i class="sea"></i><i class="w" style="top:64%"></i><i class="w" style="top:72%"></i>'
            '<i class="w" style="top:83%"></i>',
    'night': '<i class="moon"></i>' + _bl([(3, 8, 12), (12, 6, 20), (19, 10, 9), (34, 7, 16), (47, 9, 11),
                                         (63, 6, 19), (76, 9, 13), (88, 10, 17)], 'bl') + '<i class="lights"></i>',
    'summer': '<i class="sun"></i><i class="tree"></i>',
    'train': '<i class="wire"></i><i class="pole"></i><i class="rails"></i>',
    'glass': _bl([(4, 10, 40), (16, 8, 58), (26, 12, 34), (41, 9, 64), (53, 13, 46), (69, 8, 72),
                  (79, 12, 38), (93, 7, 52)], 'b'),
    'dusk': '<i class="m1"></i><i class="m2"></i><i class="fire"></i>',
    'echo': '',
    'shift': '<i class="win2"></i><i class="win"></i><i class="fig"></i>',
    'rust': ''.join(f'<i class="pyl" style="left:{l}%;height:{h}%;top:{55 - h}%"></i>'
                    for l, h in ((14, 24), (40, 16), (60, 11), (75, 8), (86, 6))),
    'salt': '<i class="line"></i><i class="fig"></i>',
    'snow': '<i class="gnd"></i>' + ''.join(f'<i class="t" style="left:{l}%;height:{h}%"></i>'
                                            for l, h in ((10, 30), (16, 22), (70, 26), (78, 34), (85, 20))),
}


def scene(kind, style=''):
    return f'<div class="scene sc-{kind}" style="{style}">{SCENES[kind]}</div>'


FILMS = {
    'harbor': ('雾港', 'harbor', ''),
    'night': ('长夜将尽', 'night', 'v'),
    'summer': ('第七个夏天', 'summer', 'dk'),
    'train': ('慢车', 'train', 'top'),
    'glass': ('玻璃城', 'glass', 'dk'),
    'dusk': ('远山灯火', 'dusk', 'v'),
    'echo': ('回声测距', 'echo', ''),
    'shift': ('晚班', 'shift', 'top'),
    'tide': ('潮汐表', 'tide', 'v'),
    'rust': ('无人区信号', 'rust', ''),
    'salt': ('盐湖', 'salt', 'dk top'),
    'snow': ('冬至以后', 'snow', 'v dk'),
}


def poster(key, cls='', style=''):
    title, sc, v = FILMS[key]
    return f'<div class="poster {cls}" style="{style}">{scene(sc)}<div class="pt {v}">{title}</div></div>'


def card(sc, title, meta, prog=None, cls='', glyph=False, filt='', meta_left=False):
    p = f'<div class="prog"><b style="width:{prog}%"></b></div>' if prog is not None else ''
    g = f'<div class="playglyph">{ic("play")}</div>' if glyph else ''
    st = f'filter:{filt}' if filt else ''
    ml = ' l' if meta_left else ''
    return (f'<div class="card {cls}"><div class="img">{scene(sc, st)}{p}{g}</div>'
            f'<div class="cap"><span class="tt">{title}</span><span class="mt{ml}">{meta}</span></div></div>')


# ---------------------------------------------------------------- QR placeholder
def qr(size=248, seed=7):
    n = 25
    rnd = random.Random(seed)
    cells = [[rnd.random() < 0.48 for _ in range(n)] for _ in range(n)]

    def finder(x0, y0):
        for y in range(-1, 8):
            for x in range(-1, 8):
                xx, yy = x0 + x, y0 + y
                if 0 <= xx < n and 0 <= yy < n:
                    edge = x in (0, 6) or y in (0, 6)
                    core = 2 <= x <= 4 and 2 <= y <= 4
                    cells[yy][xx] = (0 <= x <= 6 and 0 <= y <= 6) and (edge or core)
    finder(0, 0)
    finder(n - 7, 0)
    finder(0, n - 7)
    for i in range(8, n - 8):
        cells[6][i] = cells[i][6] = i % 2 == 0
    d = ''.join(f'M{x} {y}h1v1h-1z' for y in range(n) for x in range(n) if cells[y][x])
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 {n} {n}" shape-rendering="crispEdges">'
            f'<path d="{d}" fill="#111"/></svg>')


# ---------------------------------------------------------------- room + frame
GRAIN = ('<svg class="grain" width="1600" height="1000"><filter id="grain-f"><feTurbulence type="fractalNoise" '
         'baseFrequency=".85" numOctaves="2" stitchTiles="stitch"/><feColorMatrix type="saturate" values="0"/>'
         '</filter><rect width="1600" height="1000" filter="url(#grain-f)"/></svg>')

_BOOKS = ['#7a5a48', '#a39a8a', '#56606a', '#c5b9a5', '#6e7b6a', '#8b6f5c', '#b0a796', '#5b5148']


def _books(top, seed):
    r = random.Random(seed)
    us = ''.join(f'<u style="height:{r.randint(44, 70)}px;background:{r.choice(_BOOKS)}"></u>' for _ in range(r.randint(6, 9)))
    return f'<span class="books" style="top:{top}px">{us}</span>'


ROOM = ('<div class="room"><i class="wall"></i><i class="spill"></i><i class="ceil"></i><i class="win"></i>'
        '<i class="tree"></i><i class="curtain"></i>'
        '<i class="shelf">' + ''.join(f'<b style="top:{t}px"></b>' for t in (130, 270, 410)) +
        _books(60, 1) + _books(200, 2) + _books(340, 3) + '</i>'
        '<i class="lamp"></i><i class="shade"></i><i class="floor"></i><i class="base"></i>'
        '<i class="plant"></i><i class="pot"></i></div>' + GRAIN)

JS = """<script>(function(){var c=document.querySelector('.canvas');
function fit(){var s=Math.min(innerWidth/1600,innerHeight/1000);c.style.transform='translate('+(innerWidth-1600*s)/2+'px,'+(innerHeight-1000*s)/2+'px) scale('+s+')'}
addEventListener('resize',fit);fit();var D=document.body.getAttribute('data-default');
function st(){var s=(location.hash||'#'+D).slice(1);document.body.setAttribute('data-state',s);
document.querySelectorAll('[data-st]').forEach(function(e){e.hidden=e.getAttribute('data-st').split(' ').indexOf(s)<0});
document.querySelectorAll('.anno.st a').forEach(function(a){a.classList.toggle('on',a.getAttribute('href')=='#'+s)})}
addEventListener('hashchange',st);st()})();</script>"""


def page(title, body, default='default', css='', head_note=''):
    return ('<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width,initial-scale=1"><title>{title}</title>'
            f'<style>{BASE_CSS}{css}</style></head><body data-default="{default}">{icon_sprite()}'
            f'<div class="canvas">{body}</div>{JS}</body></html>')


def annos(title, sub, states):
    links = ''.join(f'<a href="#{sid}">{label}</a>' for sid, label, _ in states)
    notes = ''.join(f'<div class="anno note" data-st="{sid}">{note}</div>' for sid, _, note in states if note)
    return (f'<div class="anno t"><b>{title}</b> <span>· {sub}</span></div>'
            f'<a class="anno home" href="index.html">总览</a>'
            f'<div class="anno st">状态：{links}</div>{notes}')


def panel_page(title, sub, states, inner, css='', default=None):
    default = default or states[0][0]
    body = (ROOM + '<div class="stage"><div class="panel">' + inner + '</div><div class="handle"></div></div>'
            + annos(title, sub, states))
    return page(f'depth3d · {title}', body, default, css)


def nav(sel=None, solid=True, gear=False, hover=None):
    tabs = ''.join(f'<span class="tab{" sel" if t == sel else ""}{" is-hover" if t == hover else ""}">{t}</span>'
                   for t in ('首页', '媒体库', '投屏'))
    return (f'<div class="nav{" solid" if solid else ""}">{tabs}<div class="nav-r">'
            f'<span class="ibtn">{ic("search")}</span><span class="ibtn{" sel" if gear else ""}">{ic("gear")}</span>'
            '</div></div>')


def cursor(x, y):
    return f'<div class="cursor" style="left:{x}px;top:{y}px"></div>'


def row(label, sub=None, lead=None, right='', cls='', style=''):
    l = ic(lead, cls='lead') if lead else ''
    s = f'<div class="sub">{sub}</div>' if sub else ''
    return (f'<div class="row {cls}" style="{style}">{l}<div class="main"><div class="label one">{label}</div>{s}</div>'
            f'{right}</div>')


def seg(opts, sel, hover=None):
    return '<span class="seg">' + ''.join(
        f'<span class="{"sel" if o == sel else ""}{" is-hover" if o == hover else ""}">{o}</span>' for o in opts) + '</span>'


def chev():
    return f'<span class="val">{ic("chev-r")}</span>'
