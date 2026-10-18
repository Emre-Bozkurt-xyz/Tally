package tally.server.web

import kotlinx.html.FlowContent
import kotlinx.html.HtmlBlockTag
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.unsafe
import tally.core.model.Money
import kotlin.math.absoluteValue

// The dashboard's look, shared with the app (app/src/main/kotlin/tally/app/ui/theme): warm paper
// and ink, one green, Geist with mono micro-labels, big amounts with small cents, pills and cards.

/** "$1,204" with ".56" smaller. Money in shows "+" and green. */
fun FlowContent.amount(cents: Long, extra: String = "") {
    val text = if (cents < 0) "+${Money.format(-cents)}" else Money.format(cents)
    val dot = text.lastIndexOf('.')
    span("amt${if (cents < 0) " in" else ""}${if (extra.isEmpty()) "" else " $extra"}") {
        +text.substring(0, dot)
        small { +text.substring(dot) }
    }
}

/** A round letter avatar in one of six soft tints, picked by [key] (a top-level category, so a group shares a color). */
fun FlowContent.avatar(name: String, key: String?, size: String = "") {
    val tint = ((key ?: name).hashCode().absoluteValue) % 6
    span("avatar t$tint $size") { +(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·") }
}

fun FlowContent.monoLabel(text: String, extra: String = "") = span("mono-label $extra") { +text }

/** The page title: a mono label over a big heading, with actions on the right. */
fun FlowContent.pageHead(label: String, title: String, actions: (HtmlBlockTag.() -> Unit)? = null) {
    div("page-head") {
        div {
            monoLabel(label)
            h1 { +title }
        }
        actions?.let { div("head-actions") { it() } }
    }
}

/** A status pill ("REVIEW", "ASK"). */
fun FlowContent.pill(text: String, tone: String = "") = span("pill $tone") { +text }

/** One of the line icons from the app (24 grid, stroke in currentColor). */
fun FlowContent.icon(name: String) = span("ico") { unsafe { +svg(name) } }

fun svg(name: String): String {
    val paths = ICONS[name] ?: return ""
    return """<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" """ +
        """stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">""" +
        paths.joinToString("") { """<path d="$it"/>""" } + "</svg>"
}

private val ICONS = mapOf(
    "home" to listOf(
        "M4 10.2a2 2 0 0 1 .7-1.5l6-5.2a2 2 0 0 1 2.6 0l6 5.2a2 2 0 0 1 .7 1.5V19a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z",
        "M9.5 21v-6.5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1V21",
    ),
    "activity" to listOf("M9 6h11", "M9 12h11", "M9 18h11", "M4.5 6h.01", "M4.5 12h.01", "M4.5 18h.01"),
    "inbox" to listOf(
        "M21.5 12.5h-5.5l-1.8 2.8h-4.4l-1.8-2.8H2.5",
        "M5.6 5.2 2.5 12.5V18a2 2 0 0 0 2 2h15a2 2 0 0 0 2-2v-5.5l-3.1-7.3A2 2 0 0 0 16.6 4H7.4a2 2 0 0 0-1.8 1.2z",
    ),
    "wand" to listOf("M15 4V2", "M15 16v-2", "M8 9h2", "M20 9h2", "M17.8 11.8 19 13", "M17.8 6.2 19 5", "M3 21l9-9", "M12.2 6.2 11 5"),
    "file" to listOf("M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z", "M14 3v6h6", "M8 13h8", "M8 17h5"),
    "back" to listOf("M15 18l-6-6 6-6"),
    "forward" to listOf("M9 18l6-6-6-6"),
    "check" to listOf("M20 6 9 17l-5-5"),
    "search" to listOf("M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z", "M20 20l-4-4"),
    "upload" to listOf("M12 15V3", "M7 8l5-5 5 5", "M5 21h14"),
    "close" to listOf("M18 6 6 18", "M6 6l12 12"),
)

/** Tally's mark: four strokes and a strike. */
const val MARK_SVG = """<svg viewBox="0 0 108 108" width="30" height="30" aria-hidden="true"><rect width="108" height="108" rx="30" fill="var(--hero)"/>""" +
    """<path d="M41 38v32M49 38v32M57 38v32M65 38v32" stroke="var(--on-hero)" stroke-width="5" stroke-linecap="round"/>""" +
    """<path d="M35 64 71 44" stroke="var(--hero-accent)" stroke-width="5" stroke-linecap="round"/></svg>"""

const val CSS = """
@font-face { font-family: Geist; src: url(/assets/geist.ttf) format("truetype"); font-weight: 100 900; font-display: swap; }
@font-face { font-family: "Geist Mono"; src: url(/assets/geist-mono.ttf) format("truetype"); font-weight: 100 900; font-display: swap; }
:root {
  --bg:#F4F2ED; --card:#FFFFFF; --ink:#1B1A17; --muted:#6E6A62; --faint:#A9A499; --line:#E8E4DC; --chip:#E9E6DF; --chip-strong:#DCD8CE;
  --accent:#2F6B4E; --on-accent:#FFFFFF; --accent-soft:#DDEADF; --on-accent-soft:#173A29;
  --warn:#A3661A; --warn-soft:#F4E3C8; --on-warn-soft:#4A2D06; --over:#B3432E; --over-soft:#F5DDD6; --on-over-soft:#5C1B10;
  --hero:#1B1A17; --on-hero:#F4F2ED; --on-hero-muted:#A8A398; --hero-accent:#8CCBA6; --hero-track:#34322D;
  --nav:#1B1A17; --on-nav:#9D988D; --nav-on:#F4F2ED; --on-nav-on:#1B1A17;
  --primary:#1B1A17; --on-primary:#F7F5F0; --shadow:0 1px 2px rgba(27,26,23,.04), 0 8px 24px rgba(27,26,23,.05);
  --t0:#DCE7DC; --t0f:#2F5A41; --t1:#F0E4CF; --t1f:#6B4E1F; --t2:#F2DDD4; --t2f:#7A3A26;
  --t3:#DDE3EA; --t3f:#33475B; --t4:#E9DFE8; --t4f:#5A3A5A; --t5:#E6E6D2; --t5f:#4F5226;
  color-scheme: light;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg:#11100E; --card:#1B1A17; --ink:#EFEBE3; --muted:#A29D93; --faint:#6A665E; --line:#2A2825; --chip:#262421; --chip-strong:#33312D;
    --accent:#8CCBA6; --on-accent:#0E2A1C; --accent-soft:#1F3529; --on-accent-soft:#C3E7D0;
    --warn:#E0A85A; --warn-soft:#3D2C14; --on-warn-soft:#F4D9AE; --over:#EE917D; --over-soft:#4A2017; --on-over-soft:#F8D3CA;
    --hero:#ECE7DD; --on-hero:#171614; --on-hero-muted:#6F6A60; --hero-accent:#2F6B4E; --hero-track:#D5CFC3;
    --nav:#262421; --on-nav:#8C877D; --nav-on:#EFEBE3; --on-nav-on:#171614;
    --primary:#EFEBE3; --on-primary:#171614; --shadow:none;
    --t0:#22332A; --t0f:#A9D3B6; --t1:#382E1F; --t1f:#E3C792; --t2:#3B251E; --t2f:#EBAE98;
    --t3:#232B35; --t3f:#AFC2D6; --t4:#32263A; --t4f:#D6B6D6; --t5:#2E2F20; --t5f:#CBCD97;
    color-scheme: dark;
  }
}
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--ink); font: 15px/1.45 Geist, system-ui, sans-serif; font-feature-settings:"tnum";
       -webkit-font-smoothing: antialiased; }
a { color: inherit; text-decoration: none; }
a.link { color: var(--accent); }
h1 { font-size: 2.1rem; line-height:1.1; letter-spacing:-.04em; font-weight:600; margin:.35rem 0 0; }
h2 { font-size: 1.15rem; letter-spacing:-.02em; font-weight:600; margin: 1.5rem 0 .75rem; }
p { margin: .4rem 0; }
.muted { color: var(--muted); }
.mono-label { font: 500 11px/1.3 "Geist Mono", ui-monospace, monospace; letter-spacing:.12em; text-transform: uppercase; color: var(--muted); }
.mono, code { font-family: "Geist Mono", ui-monospace, monospace; font-size: .88em; }
.ico { display:inline-flex; width:20px; height:20px; flex: none; }
.ico svg { width:100%; height:100%; }

/* Layout: a sidebar on wide screens, a floating pill bar on narrow ones. */
.shell { display:grid; grid-template-columns: 232px 1fr; min-height:100vh; }
.sidebar { position:sticky; top:0; height:100vh; display:flex; flex-direction:column; gap:1.5rem; padding:1.5rem 1rem; border-right:1px solid var(--line); }
.brand { display:flex; align-items:center; gap:.6rem; font-weight:600; font-size:1.15rem; letter-spacing:-.02em; padding:0 .5rem; }
.nav { display:flex; flex-direction:column; gap:.2rem; }
.nav a { display:flex; align-items:center; gap:.75rem; padding:.6rem .75rem; border-radius:999px; color:var(--muted); font-weight:500; }
.nav a:hover { background:var(--chip); color:var(--ink); }
.nav a.active { background:var(--primary); color:var(--on-primary); }
.nav .count { margin-left:auto; background:var(--over); color:#fff; border-radius:999px; padding:0 .45rem; font-size:.75rem; line-height:1.4rem; min-width:1.4rem; text-align:center; }
.who { margin-top:auto; padding:0 .5rem; }
main { padding: 1.75rem 2.25rem 4rem; max-width: 1240px; width:100%; }
.page-head { display:flex; align-items:flex-end; justify-content:space-between; gap:1rem; margin-bottom:1.5rem; flex-wrap:wrap; }
.head-actions { display:flex; gap:.5rem; align-items:center; flex-wrap:wrap; }
.live-banner { position:fixed; bottom:1.25rem; left:50%; transform:translateX(-50%); background:var(--primary); color:var(--on-primary);
               padding:.6rem 1.1rem; border-radius:999px; z-index:5; box-shadow:0 8px 24px rgba(0,0,0,.25); font-weight:500; }
.live-banner a { text-decoration:underline; }

/* Cards */
.card { background:var(--card); border-radius:24px; padding:1.25rem 1.35rem; box-shadow:var(--shadow); min-width:0; }
.card > h2:first-child, .card > .card-head:first-child { margin-top:0; }
.card-head { display:flex; align-items:center; justify-content:space-between; gap:.75rem; margin-bottom:1rem; flex-wrap:wrap; }
.card-head h2 { margin:0; }
.hero { background:var(--hero); color:var(--on-hero); border-radius:30px; padding:1.6rem 1.75rem; display:flex; flex-direction:column; gap:.9rem; justify-content:space-between; min-height:15rem; }
.hero .big + * { margin-top:auto; }
.hero .mono-label { color:var(--on-hero-muted); }
.hero .row-between { display:flex; justify-content:space-between; gap:1rem; align-items:center; flex-wrap:wrap; }
.hero .big { font-size:4.2rem; line-height:1; letter-spacing:-.05em; font-weight:600; }
.hero .big small { font-size:.5em; opacity:.45; letter-spacing:-.02em; }
.hero .note { color:var(--on-hero); }
.hero .note .muted { color:var(--on-hero-muted); }
.stack { display:flex; flex-direction:column; gap:1rem; }
.stat .value { font-size:1.9rem; letter-spacing:-.04em; font-weight:600; margin-top:.35rem; }
.stat .sub { color:var(--muted); font-size:.85rem; margin-top:.15rem; }
.stat.attention { background:var(--warn-soft); color:var(--on-warn-soft); }
.stat.attention .mono-label, .stat.attention .sub { color:var(--on-warn-soft); opacity:.8; }
.overview { display:grid; grid-template-columns: repeat(12, 1fr); gap:1rem; }
.span-4 { grid-column: span 4; } .span-5 { grid-column: span 5; } .span-7 { grid-column: span 7; } .span-8 { grid-column: span 8; } .span-12 { grid-column: 1 / -1; }
.chart-box { position:relative; height:260px; }
.legend { display:flex; gap:1.25rem; margin-top:.75rem; }
.legend span { display:inline-flex; align-items:center; gap:.45rem; }
.legend .swatch { display:inline-block; width:14px; height:2px; background:var(--ink); }
.legend .swatch.dashed { background: repeating-linear-gradient(90deg, var(--faint) 0 4px, transparent 4px 7px); }

/* Amounts */
.amt { white-space:nowrap; font-weight:500; letter-spacing:-.01em; }
.amt small { font-size:.78em; opacity:.6; }
.amt.in { color:var(--accent); }
.amt.lg { font-size:1.35rem; font-weight:600; letter-spacing:-.03em; }

/* Avatars, pills */
.avatar { display:inline-flex; align-items:center; justify-content:center; width:40px; height:40px; border-radius:50%; font-weight:500; flex:none; font-size:1rem; }
.avatar.sm { width:32px; height:32px; font-size:.85rem; }
.t0 { background:var(--t0); color:var(--t0f); } .t1 { background:var(--t1); color:var(--t1f); } .t2 { background:var(--t2); color:var(--t2f); }
.t3 { background:var(--t3); color:var(--t3f); } .t4 { background:var(--t4); color:var(--t4f); } .t5 { background:var(--t5); color:var(--t5f); }
.pill { display:inline-block; font: 500 10px/1.3 "Geist Mono", monospace; letter-spacing:.1em; text-transform:uppercase; padding:.2rem .55rem; border-radius:999px;
        background:var(--chip); color:var(--muted); white-space:nowrap; vertical-align:middle; }
.pill.warn { background:var(--warn-soft); color:var(--on-warn-soft); }
.pill.good { background:var(--accent-soft); color:var(--on-accent-soft); }
.pill.bad { background:var(--over-soft); color:var(--on-over-soft); }
.hero .pill.good { background: color-mix(in srgb, var(--hero-accent) 30%, transparent); color:var(--on-hero); }
.hero .pill.warn { background: color-mix(in srgb, var(--warn) 30%, transparent); color:var(--on-hero); }
.tag { display:inline-block; background:var(--chip); color:var(--muted); border-radius:999px; padding:0 .5rem; margin-left:.35rem; font-size:.75rem; }

/* Progress */
.track { position:relative; height:6px; border-radius:999px; background:var(--chip); }
.track > .fill { position:absolute; inset:0 auto 0 0; border-radius:999px; background:var(--accent); min-width:6px; }
.track.thin { height:4px; }
.fill.warning { background:var(--warn) !important; } .fill.over { background:var(--over) !important; }
.track .tick { position:absolute; top:-5px; bottom:-5px; width:2px; border-radius:1px; background:var(--muted); }
.hero .track { background:var(--hero-track); } .hero .track > .fill { background:var(--hero-accent); } .hero .track .tick { background:var(--on-hero-muted); }

/* Lists of rows inside a card */
.rows { display:flex; flex-direction:column; }
.rows > .r { display:flex; align-items:center; gap:.9rem; padding:.7rem 0; border-top:1px solid var(--line); }
.rows > .r:first-child { border-top:none; padding-top:.2rem; }
.r .grow { flex:1; min-width:0; }
.r .title { font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.r .sub { color:var(--muted); font-size:.82rem; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.r .end { text-align:right; display:flex; flex-direction:column; align-items:flex-end; gap:.25rem; }
.cat-row { display:grid; grid-template-columns:auto 1fr auto; gap:.25rem .8rem; align-items:center; padding:.55rem 0; }
.cat-row .track { grid-column: 2 / 4; }
.cat-row.child { padding:.2rem 0 .2rem 2.7rem; grid-template-columns:1fr auto; font-size:.88rem; color:var(--muted); }
.cat-row a:hover .title { text-decoration:underline; }
.cat-row .title { font-weight:500; }

/* Controls */
select, input, textarea, button { font:inherit; color:inherit; }
input, select, textarea { background:var(--chip); border:1px solid transparent; border-radius:14px; padding:.55rem .8rem; max-width:100%; outline:none; }
input:focus, select:focus, textarea:focus { border-color:var(--ink); background:var(--card); }
select { appearance:none; padding-right:2rem; cursor:pointer;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23A9A499' stroke-width='2' stroke-linecap='round'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E");
  background-repeat:no-repeat; background-position: right .6rem center; background-size: 16px; }
input[type=checkbox] { appearance:none; width:38px; height:22px; border-radius:999px; background:var(--chip-strong); position:relative; padding:0; cursor:pointer; border:none; flex:none; }
input[type=checkbox]::after { content:""; position:absolute; top:3px; left:3px; width:16px; height:16px; border-radius:50%; background:var(--card); transition: left .15s; }
input[type=checkbox]:checked { background:var(--accent); }
input[type=checkbox]:checked::after { left:19px; background:var(--on-accent); }
button, .btn { display:inline-flex; align-items:center; gap:.45rem; justify-content:center; border:none; background:var(--chip); color:var(--ink);
               border-radius:999px; padding:.55rem 1.05rem; cursor:pointer; font-weight:500; white-space:nowrap; }
button:hover, .btn:hover { background:var(--chip-strong); }
button.primary, .btn.primary { background:var(--primary); color:var(--on-primary); }
button.primary:hover { opacity:.9; }
button.accent { background:var(--accent); color:var(--on-accent); }
button.quiet { background:transparent; color:var(--muted); }
button.quiet:hover { background:var(--chip); color:var(--ink); }
button.round { width:40px; height:40px; padding:0; }
a.round { display:inline-flex; align-items:center; justify-content:center; width:40px; height:40px; border-radius:50%; background:var(--chip); }
a.round:hover { background:var(--chip-strong); }
a.round.disabled { opacity:.35; pointer-events:none; }
button[disabled] { opacity:.35; cursor:default; }
.segmented { display:inline-flex; background:var(--chip); border-radius:999px; padding:3px; }
.segmented button { background:transparent; color:var(--muted); padding:.35rem .9rem; font-size:.85rem; }
.segmented button.on { background:var(--primary); color:var(--on-primary); }

/* Tables (the ledger, statement previews) */
table { width:100%; border-collapse:collapse; }
th { text-align:left; font: 500 11px/1.3 "Geist Mono", monospace; letter-spacing:.12em; text-transform:uppercase; color:var(--muted); padding:.5rem .6rem; }
td { padding:.6rem; border-top:1px solid var(--line); vertical-align:middle; }
tr.day td { border-top:none; padding:1.2rem .6rem .35rem; }
tr.day td .mono-label { color:var(--muted); }
tr.day + tr td { border-top:none; }
td.num, th.num { text-align:right; white-space:nowrap; }
td.who-cell { display:flex; align-items:center; gap:.8rem; min-width:14rem; }
td.who-cell .note { color:var(--muted); font-size:.85rem; }
tr.captured td.who-cell .title::after { content:"Review"; margin-left:.5rem; font: 500 10px/1.3 "Geist Mono", monospace; letter-spacing:.1em; text-transform:uppercase;
                                         padding:.15rem .5rem; border-radius:999px; background:var(--warn-soft); color:var(--on-warn-soft); vertical-align:middle; }
tfoot td { font-weight:600; border-top:1px solid var(--line); padding-top:.9rem; }
select.category { min-width:11rem; width:100%; padding:.4rem 2rem .4rem .8rem; border-radius:999px; font-size:.9rem; }
.nowrap { white-space:nowrap; }
table.compact td { padding:.5rem .4rem; }

/* Filters */
.filters { display:flex; flex-wrap:wrap; gap:.6rem; align-items:flex-end; margin-bottom:1rem; }
.filters label { display:flex; flex-direction:column; gap:.3rem; }
.filters label > span { font: 500 10px/1.3 "Geist Mono", monospace; letter-spacing:.12em; text-transform:uppercase; color:var(--muted); padding-left:.3rem; }
.filters .search { flex:1; min-width:12rem; }
.filters .search input { width:100%; }

/* Inbox */
.inbox { display:grid; grid-template-columns: repeat(auto-fill, minmax(420px, 1fr)); gap:1rem; }
.item { display:flex; flex-direction:column; gap:.9rem; }
.item-top { display:flex; align-items:center; gap:.9rem; }
.item .merchant { font-weight:600; font-size:1.05rem; letter-spacing:-.01em; }
.item .details { color:var(--muted); font-size:.85rem; }
.suggestion { display:flex; align-items:center; gap:.6rem; background:var(--bg); border-radius:16px; padding:.65rem .9rem; flex-wrap:wrap; }
.suggestion .what { font-weight:500; flex:1; min-width:8rem; }
.item-actions { display:flex; gap:.5rem; align-items:center; flex-wrap:wrap; }
.item-actions select { flex:1; min-width:10rem; }
.item.done { flex-direction:row; align-items:center; color:var(--muted); }
.item.done .ico { color:var(--accent); }
.badge { background:var(--chip); color:var(--muted); font-size:.72rem; border-radius:999px; padding:.1rem .5rem; margin-left:.35rem; }

/* Statements */
.upload { display:flex; gap:1rem; align-items:center; flex-wrap:wrap; border:1.5px dashed var(--chip-strong); border-radius:24px; padding:1.5rem; background:var(--card); }
.upload input[type=file] { flex:1; min-width:14rem; background:transparent; padding:0; }
.upload input[type=file]::file-selector-button { font:inherit; border:none; background:var(--chip); border-radius:999px; padding:.5rem 1rem; margin-right:.8rem; cursor:pointer; }
.cards { display:grid; grid-template-columns:repeat(auto-fit, minmax(170px, 1fr)); gap:1rem; margin:1rem 0; }
.result { padding:.7rem 1rem; border-radius:16px; margin:.75rem 0; }
.result.ok { background:var(--accent-soft); color:var(--on-accent-soft); }
.result.error { background:var(--over-soft); color:var(--on-over-soft); }
.result.ok a, .result.error a { text-decoration:underline; }

.htmx-indicator { display:none; }
.htmx-request .htmx-indicator, .htmx-request.htmx-indicator { display:inline; }
.sr { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0 0 0 0); }

@media (max-width: 1100px) {
  .span-4, .span-5, .span-7, .span-8 { grid-column: 1 / -1; }
}
@media (max-width: 900px) {
  .shell { display:block; }
  .sidebar { position:fixed; top:auto; bottom:14px; left:16px; right:16px; height:auto; padding:6px; border:none; border-radius:999px;
             background:var(--nav); flex-direction:row; z-index:4; box-shadow:0 10px 30px rgba(0,0,0,.3); }
  .sidebar .brand, .sidebar .who { display:none; }
  .nav { flex-direction:row; justify-content:space-between; width:100%; gap:0; }
  .nav a { color:var(--on-nav); padding:.7rem .9rem; }
  .nav a:hover { background:transparent; color:var(--nav-on); }
  .nav a.active { background:var(--nav-on); color:var(--on-nav-on); }
  .nav a:not(.active) .label { display:none; }
  .nav .count { margin-left:0; }
  main { padding: 1.25rem 1rem 7rem; }
  h1 { font-size:1.75rem; }
  .hero .big { font-size:3.2rem; }
  .hide-sm { display:none; }
  .inbox { grid-template-columns: 1fr; }
  td.who-cell { min-width:0; }
  .live-banner { bottom: 6rem; }
  .filters { display:grid; grid-template-columns:1fr 1fr; }
  .filters label { min-width:0; }
  .filters input, .filters select { width:100%; }
  .filters .search { grid-column: 1 / -1; }
  .filters button { grid-column: 1 / -1; }
  /* Ledger rows become two lines: who and how much, then the category. */
  table.ledger thead { display:none; }
  table.ledger tr { display:grid; grid-template-columns:1fr auto; align-items:center; column-gap:.6rem; border-top:1px solid var(--line); padding:.55rem 0; }
  table.ledger td { border:none; padding:.15rem 0; }
  table.ledger td.who-cell { grid-column:1; grid-row:1; }
  table.ledger td.num { grid-column:2; grid-row:1; }
  table.ledger td:nth-child(2) { grid-column:1 / -1; grid-row:2; padding-left:2.75rem; }
  table.ledger tr.day { display:flex; justify-content:space-between; border:none; padding:1rem 0 .1rem; }
  table.ledger tr.day td { padding:0; }
  table.ledger tr.day + tr { border-top:none; }
  table.ledger tfoot tr { display:flex; justify-content:space-between; }
}
@media (max-width: 560px) {
  .nav a .label { display:none; }
  .nav a { padding:.7rem; }
}
"""
