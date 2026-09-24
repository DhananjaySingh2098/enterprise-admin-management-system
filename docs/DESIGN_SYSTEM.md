# Design System

**Target:** a premium enterprise SaaS admin interface that is calm, dense enough for real work, and precise. Depth and
motion are used sparingly and always serve orientation or feedback, never decoration.

Tokens are implemented as CSS custom properties in
[`frontend/src/styles/_tokens.scss`](../frontend/src/styles/_tokens.scss). Components use tokens only and never raw
hex values, pixel sizes or durations. Shared component styles live in `src/styles/_components.scss`, `_forms.scss`
and `_shell.scss`.

## Principles

1. **Clarity first.** Data and actions come before decoration. Every visual effect must earn its place.
2. **Consistency through tokens.** One scale for each dimension: type, space, radius, shadow and motion.
3. **Quiet surfaces, confident accents.** Neutral layers, with a single primary colour for intent.
4. **Accessible by default.** WCAG 2.2 AA is the floor, not a stretch goal.
5. **Theme-agnostic components.** Light and dark are palettes over the same tokens.

## Typography

Font stack: `--app-font-sans` (Inter, falling back to the system UI font) and `--app-font-mono` for IDs, code and
tabular figures.

| Token | Size | Use |
|---|---|---|
| `--app-font-size-4xl` | 36px | Marketing or empty-state hero only |
| `--app-font-size-3xl` | 30px | Page title (h1) |
| `--app-font-size-2xl` | 24px | Section title (h2) |
| `--app-font-size-xl` | 20px | Card title (h3) |
| `--app-font-size-lg` | 18px | Lead text, KPI labels |
| `--app-font-size-md` | 16px | Body (base) |
| `--app-font-size-sm` | 14px | Table cells, form controls, secondary text |
| `--app-font-size-xs` | 12px | Captions, badges, overlines |

- Weights: 400 body, 500 UI labels, 600 headings and emphasis. Avoid 700 and above except for KPI numerals.
- Line height: `--app-line-height-tight` (1.2) for headings, `--app-line-height-normal` (1.5) for body.
- Headings of 24px and above use `letter-spacing: -0.02em`. Numbers in tables use
  `font-variant-numeric: tabular-nums`.

## Spacing scale (4px base)

`--app-space-1` 4 · `-2` 8 · `-3` 12 · `-4` 16 · `-5` 20 · `-6` 24 · `-8` 32 · `-10` 40 · `-12` 48 · `-16` 64

- Inside components: 8–16px. Between related groups: 24px. Between page sections: 32–48px.
- Density modes (Phase 5) will scale the component-internal spacing only.

## Radius scale

| Token | Value | Use |
|---|---|---|
| `--app-radius-sm` | 6px | Inputs, buttons, chips, the brand mark |
| `--app-radius-md` | 10px | Menus, popovers, small cards |
| `--app-radius-lg` | 16px | Cards, panels, dialogs |
| `--app-radius-pill` | 999px | Status badges, toggles, avatars |

Nested radius = outer radius − padding, so inner corners stay concentric.

## Surfaces

| Token | Role |
|---|---|
| `--app-bg` | Page canvas |
| `--app-surface` | Default card, panel, top bar and sidebar |
| `--app-surface-elevated` | Menus, popovers, dialogs, sticky headers (anything floating above surfaces) |
| `--app-surface-sunken` | Wells, code blocks, table header rows, input backgrounds |

Hierarchy comes from surface tone, border and shadow working together. Do not stack more than three levels.

## Borders

- `--app-border`: default 1px hairline for cards, dividers and table rows.
- `--app-border-strong`: input outlines and hovered interactive borders.
- Focus is always `--app-focus-ring` (2px outline, 2px offset). It is never removed and never replaced by a colour
  change alone.

## Shadows

| Token | Use |
|---|---|
| `--app-shadow-sm` | Resting cards |
| `--app-shadow-md` | Hovered or raised cards, dropdowns |
| `--app-shadow-lg` | Dialogs, command palette, toasts |

Light-theme shadows are soft and low-opacity. Dark-theme shadows are deeper and combined with a faint 1px light
edge, because shadows alone disappear on dark backgrounds.

## Colour — light theme (default)

| Token | Value | Notes |
|---|---|---|
| `--app-bg` | `#f6f7f9` | |
| `--app-surface` | `#ffffff` | |
| `--app-text` | `#111827` | ≈ 16:1 on surface |
| `--app-text-muted` | `#5b6474` | ≥ 4.5:1 on surface and bg |
| `--app-primary` | `#3b5bdb` | ≥ 4.5:1 with `--app-on-primary` |
| `--app-border` | `#e3e6eb` | |
| `--app-success` / `-warning` / `-danger` | `#16a34a` / `#d97706` / `#dc2626` | Always paired with an icon or label, never colour alone |

## Dark theme readiness

- The dark palette already exists in `_tokens.scss`. It is applied when the OS prefers dark, unless
  `<html data-theme="light">` is set, and `<html data-theme="dark">` forces it.
- See "Theme system" below.
- Dark surfaces are blue-tinted near-black (`#0b0f17` → `#182033`), not pure black. Text is `#e7eaf0`, not pure white.
- The primary colour is lightened (`#7c93ff`) to keep contrast on dark surfaces.
- Images and charts must read their colours from tokens so they switch themes automatically.

## Responsive breakpoints

| Name | Min width | Layout intent |
|---|---|---|
| `xs` | 0 | Single column, bottom or overlay navigation |
| `sm` | 600px | Single column, wider forms |
| `md` | 900px | Collapsible icon sidebar, 2-column grids |
| `lg` | 1200px | Full sidebar, 3–4 column dashboard grid |
| `xl` | 1536px | Content max-width caps, with extra whitespace in the gutters |

Design mobile-first with `min-width` media queries. Tables become stacked cards or scroll horizontally inside their
container below `md`. The page body itself never scrolls horizontally. Touch targets are at least 44×44px.

## Accessibility

- WCAG 2.2 AA contrast: 4.5:1 for text, 3:1 for large text, UI component borders and focus indicators.
- Full keyboard operation with a logical tab order. A "Skip to content" link is present in the shell. Dialogs trap
  focus and restore it when they close.
- Semantic HTML first (`button`, `nav`, `main`, `table`). ARIA only where semantics fall short. Live regions
  (`role="status"`) announce asynchronous state such as the API badge.
- Never convey meaning by colour alone. Respect `prefers-reduced-motion`, `prefers-color-scheme` and browser zoom up to
  200%.
- Form fields always have visible labels. Errors are linked with `aria-describedby` and announced.

## Motion

Motion explains change: where something came from, what responded, and what is loading. It stays within
**150–400ms**.

| Token | Value | Use |
|---|---|---|
| `--app-duration-fast` / `--app-transition-fast` | 150ms | Hover, press, focus, colour and opacity changes |
| `--app-duration-normal` / `--app-transition-normal` | 250ms | Dropdowns, tooltips, sidebar collapse, card lift |
| `--app-duration-slow` / `--app-transition-slow` | 400ms | Page transitions, dialogs, first-load stagger |
| `--app-ease-standard` | `cubic-bezier(0.2, 0, 0, 1)` | Default: quick start, gentle settle |
| `--app-ease-emphasized` | `cubic-bezier(0.3, 0, 0, 1.2)` | Rare, subtle overshoot for delight moments (e.g. success) |

Planned effects (Phase 4). These use CSS transitions and Angular's built-in `animate.enter`/`animate.leave`, with no
heavy animation library:

- **Page fade/slide:** a new route fades in with a 4–8px upward translate over 250–400ms. The outgoing page fades
  without moving.
- **Card stagger:** on first dashboard load, cards enter 40–60ms apart, capped at about 8 items.
- **Hover lift:** interactive cards rise `translateY(-2px)` and move from `--app-shadow-sm` to `--app-shadow-md` over
  150–250ms.
- **Sidebar transitions:** collapsing or expanding animates width or transform over 250ms. Labels fade out before the
  width shrinks.
- **Button micro-interactions:** press gives `scale(0.98)` for 150ms, and loading swaps the label for a spinner without
  changing the button width.

Rules:

- Animate only `transform` and `opacity` (plus colour and shadow for hover states). Never animate layout properties
  such as width, top or height on large surfaces, except for the sidebar.
- Nothing blocks input while it animates, and nothing loops forever except progress indicators.
- **`prefers-reduced-motion: reduce` is always honoured.** Duration tokens collapse to 0ms and a global rule neutralises
  animations. Fades may remain, but movement, parallax and tilt are removed.

## Subtle 3D interaction rules

Depth communicates elevation and interactivity. It is not a visual gimmick.

- Depth comes from the shadow scale plus tone. Use at most three elevation levels on a screen.
- Optional pointer tilt is limited to hero or KPI cards: maximum 2–3° of rotation with `perspective: 1000px`, driven by
  pointer position, and returning to flat on leave over 250ms. Never apply it to forms, tables or text-heavy content.
- A subtle highlight (a radial gradient that follows the pointer at ≤ 6% opacity) may accompany tilt in dark mode.
- Tilt is disabled on touch devices, under reduced motion, and whenever the element contains focusable controls that
  are being used.
- Text must never be rendered at a fractional 3D transform while it is being read. Transforms reset when content has
  focus.
- Budget: interactions hold 60fps on a mid-range laptop. Use `will-change` only during an interaction and remove it
  afterwards.

## Implemented components (Phase 3)

| Component | Notes |
|---|---|
| App shell | 16.5rem sidebar on screens ≥1024px, with an off-canvas drawer and scrim below that (Escape closes). Sticky translucent top bar with the page title, theme control (system → light → dark), user chip (avatar, name, role) and sign-out. The active nav item has a tinted background and an animated 3px indicator. |
| Data table | Semantic `<table>` with a visually hidden caption, `th[appSortHeader]` buttons exposing `aria-sort`, hover rows, skeleton loading rows, empty states and a pagination footer. Below 768px, rows become labelled cards (`data-label`). Between 768 and 1023px, the table scrolls inside its card and never widens the page. |
| Badges | Role badges (ADMIN primary, MANAGER info, USER neutral) and status pills with a dot plus text (colour is never the only signal). |
| Avatar | Initials with a deterministic per-name hue. Separate light and dark palettes. |
| Dialog / drawer | Native `<dialog>` in modal mode, so the page behind is inert. Escape and backdrop close it unless busy, and focus returns to the opener. The drawer slides in from the right and is full-width on phones. |
| Forms | Visible labels, required marks, hints and errors linked through `aria-describedby`, `aria-invalid`, server field errors mapped onto controls, and choice chips for roles. |
| Toasts | A polite live region in the bottom-right corner, auto-dismissed. |

Motion in use: page enter (6px rise, 400ms), sidebar indicator (250ms), row and card hover (150–250ms), button press
(`scale(0.98)`), and dialog/drawer enter (250ms). All durations come from tokens, so they collapse to 0ms under
`prefers-reduced-motion`.

## Theme system (Phase 4)

There are two independent axes, both set as attributes on `<html>` and both pure token overrides in `_tokens.scss`.
No stylesheet is duplicated.

| Axis | Values | Attribute |
|---|---|---|
| Appearance | System (follows `prefers-color-scheme`) · Light · Dark | `data-theme` (absent for System) |
| Preset | **Aurora** (default) · **Obsidian** · **Pearl** · **Midnight** · **Emerald**: see \"Premium visual refinement\" | `data-preset` |

- **What a preset changes:** brand (`--app-primary`, hover, on-primary, focus ring), accent (`--app-accent`), canvas
  and surfaces (`--app-bg`, `--app-surface*`), borders, muted text, chart grid and the hero glows.
- **Dark variants:** each preset defines the same keys for light and dark, so values never leak between modes. The
  dark rules win through higher specificity (`[data-preset][data-theme='dark']`, or the dark media query with
  `:not([data-theme='light'])`).
- **UI:** the header "Theme settings" popover holds native radio groups for Appearance and Theme. It is keyboard
  operable (Tab, arrow keys, Escape returns focus) and labelled for screen readers.
- **Persistence:** the choice is saved in `localStorage` (`enterprise-admin:theme`, `enterprise-admin:preset`)
  through the injectable `THEME_STORAGE`. It is not sensitive, and if storage is blocked the theme still applies for
  the session.
- **No flash:** an inline boot script in `index.html` applies the saved attributes before the first paint, verified
  by blocking the Angular bundle and reading the computed background.

## Charts (Phase 4)

These are hand-written, with no chart library. The pure maths lives in `shared/charts/chart-utils.ts` and is unit
tested.

- **Honesty:** axes always start at 0 with whole-number ticks. Lines are straight segments, with no smoothing that
  could overshoot. Shares are derived from real counts, and a zero total shows 0%.
- **Bar chart** (department headcount): plain HTML, so labels and values are real text. Bars scale to the largest
  department, inactive departments are muted and tagged, and each row is focusable with a tooltip and an aria-label
  giving its share. It shows the top 8, then "and N smaller departments".
- **Donut** (employee status): SVG arcs proportional to counts, with a legend showing label, count and share, so
  colour is never the only signal. The SVG has a full text `aria-label`. Hovering a segment or legend row highlights
  it and updates the centre value.
- **Line/area** (hiring trend): SVG drawn at the measured container width via `ResizeObserver`, so text is never
  distorted. X labels thin out to fit (at least 56px apart) and the edge labels are anchored inside the plot.
  - Interaction: hover or ←/→/Home/End moves a guide line and tooltip, and changes are announced in a polite live
    region.
  - The panel offers a "Show data table" alternative and a 12/24-month range toggle.
- **Empty data:** with zero employees the dashboard shows a compact onboarding panel with an action the role may
  perform, instead of empty charts.

## Motion & depth rules (Phase 4)

| Effect | Where | Timing |
|---|---|---|
| Fade + 8px rise | Hero, KPI cards, panels | 400ms, with a 50ms stagger via `--i` |
| Bar grow / donut draw / line draw | Charts | 400–800ms (line and donut use 1.5–2× the slow token) |
| Row stagger | Recent hires | 250ms, 40ms apart |
| Hover lift (−2px plus a deeper shadow), icon nudge | KPI cards, links | 150–250ms |
| Press feedback | Buttons | `scale(0.98)`, 150ms |

**3D tilt** (`[appTilt]`, KPI cards only):

- `perspective(900px) rotateX/rotateY`, at most 4° (the directive clamps to 5°), with the icon and value lifted
  through `translateZ`.
- A soft radial spotlight follows `--mouse-x` / `--mouse-y`.
- Active only when `(hover: hover) and (pointer: fine)` matches and reduced motion is not requested.
- Updates are throttled with `requestAnimationFrame` and fully reset on pointer leave, so nothing loops.
- Never applied to tables, charts, forms or page containers.

All durations come from tokens, so `prefers-reduced-motion` collapses them to 0ms. Reduced motion also disables tilt
and spotlight.

## Premium visual refinement (post-Phase 4)

### Presets and token vocabulary

- **Presets:** five presets × light/dark, each defining the same keys:
  - **Aurora:** cool neutrals, indigo/violet/electric blue
  - **Obsidian:** graphite, restrained violet
  - **Pearl:** warm ivory, champagne/bronze
  - **Midnight:** deep navy, cobalt
  - **Emerald:** forest-tinted neutrals, emerald
- **Keys per preset:** `bg`, `surface`, `surface-soft`, `surface-elevated`, `surface-glass`, `surface-sunken`, `text`, `text-muted`, `text-soft`, `primary`, `primary-soft`, `accent`, `accent-2`, `border`, `glow`, the ambient fields, the grid line, `chart-grid`, and the inner highlight.
- **Contrast:** every preset and mode was checked. The text, muted and soft tiers and primary/on-primary are all ≥ 4.95:1 (WCAG AA).
- **Aliases:** the short names (`--surface`, `--text-muted`, `--primary-soft`, `--glow`, `--shadow-md` …) map onto the canonical `--app-*` tokens.

### Page and surfaces

- **Page depth:** fixed `body::before` ambient radial fields plus a masked 44px grid (`body::after`). These layers are decorative only and never reduce text contrast.
- **Card tiers** (`_surfaces.scss`):

| Tier | Use |
|---|---|
| `.surface-primary` | Hero |
| `.card-analytics` | Chart panels, with a brand hairline on the top edge |
| `.kpi-card` | Metric cards: tone gradient, corner glow, and a real composition meter |
| `.card-action` | Interactive: lifts on hover and focus-within |
| `.card-compact` | Stat tiles |
| `.surface-secondary` | Quiet supporting panels |

- **Radii:** 18–22px on primary cards.

### Components

- **Shell:**
  - Sidebar: brand block, icon containers, an active pill with a glowing 3px indicator, a hover background slide, and a role footer card.
  - Glass header with a Workspace › section breadcrumb.
  - Account menu (Profile · Appearance · Sign out), built as a disclosure.
  - Theme popover: previews, a check mark and a glowing border on the selected preset.
- **Buttons:**
  - Primary uses the brand gradient, an inner highlight and glow. Secondary is a raised surface. Ghost and icon variants are also available.
  - Heights: 2.5rem, 2.125rem (sm) and 2.875rem (lg).
  - Every variant lifts 1px on hover, scales to 0.98 on press, and shows a 2px focus ring.

### Motion (entrance)

`[appReveal]` (IntersectionObserver, once) drives the entrance sequence:

| Element | Delay |
|---|---|
| Hero | 0ms |
| KPI cards | 60, 110, 160, 210ms |
| Analytics panels | 120–240ms |
| Recent hires | 180ms |
| System access | 220ms |

- The entrance uses opacity plus the individual `translate`/`scale` properties, with a 3px blur on the hero and KPI cards only. Using `translate`/`scale` lets it compose with the tilt transform.
- Charts animate from the same reveal: bars grow from 0 to their value, donut arcs draw, and the line strokes in. Every animation ends at the real data value.
- A 2.5s safety fallback means content can never stay hidden.

### Motion (ambient and pointer)

- **Hero art:** CSS keyframes only (8–18s drift and float). Parallax comes from `--mx`/`--my`, which `[appSpotlight]` writes, and only while the pointer moves.
- **3D tilt** (`[appTilt]`, KPI cards): rotateX at most 3°, rotateY at most 4°, with icon and value `translateZ`. It returns to neutral on leave. It never runs on touch, keyboard focus or reduced motion.
- **Spotlight** (hero, KPI cards, system-access card): a radial highlight that follows `--mouse-x`/`--mouse-y`.
- **JavaScript:** no loops. `requestAnimationFrame` runs only during pointer movement.

### Reduced motion

When `prefers-reduced-motion` is set:

- all reveals are immediate and chart draws are skipped
- floating, drifting and hero glow animations stop
- tilt, spotlight and parallax are disabled
- durations collapse to 0ms

## Reference redesign (Aurora lavender)

Aurora is the default and reference theme: lavender ground (`#f3f2fb`), violet primary (`#5b4de8`), violet-tinted
shadows; the dark variant uses a deep navy ground (`#0b0c18`). Other presets inherit the same composition.

- **Shell** – sidebar brand + "Manage. People. Grow.", pill navigation with a 4px gradient indicator, a decorative
  promo card (`aria-hidden`, CSS cube) and a "Need help?" disclosure. Header: role-scoped global search (hands the
  term to existing server searches via `?search=`; ⌘K/Ctrl+K), light/dark pill + theme settings, account menu.
- **Dashboard** – hero (date, greeting, real chips, refresh + Add employee, `app-hero-stage`) → four compact KPI
  cards (violet / green / orange / cyan; the watermark glyph is decorative, the thin meter is real composition) →
  Headcount (single-line bars) + Status donut → Recent hires table + System access (ADMIN) → Hiring trend.
- **Hero stage** – CSS-only 3D scene (perspective 900px, layers at translateZ −30…110px). Pointer parallax ≤3°/≤2°
  via `--mx/--my` from `[appSpotlight]`; fine pointers only; static under reduced motion. Also used on Login.
- **Load order** – sidebar 0 · header 40 · hero 80 · KPI 130 + 50ms stagger · charts 220 · recent 300 · access 340ms.
- **No invented data** – no sparklines, deltas, notifications or "this month" filters (no backing data).

### Motion + 3D polish

- **KPI tilt** – `perspective(700px)`, rotateX/Y ≤3° from the pointer, `translateY(-3px)`, four-layer hover shadow
  (tone-tinted; capped to 28% in dark themes). Follows the pointer at 120ms and eases back over ~0.55s. Hit-testing
  uses the untilted rectangle so tilted edges never flicker; any scroll ends the effect.
- **Spotlight** – `--spotlight-size` (KPI 13rem, others 22rem); applied to KPI cards, hero and System access only.
- **Hero stage** – four depth planes (ring −60px → tiles 110px). Each plane shifts in proportion to its depth
  (`--shift`) plus the ≤3° scene tilt; blobs drift against the pointer. Idle float/twinkle/orbit are CSS keyframes on
  the independent `translate`/`rotate` properties so they compose with the parallax transform.
- **Sidebar** – one `.nav-slider` pill + edge indicator slides between links; its row comes from `:has()` (no JS).
- **Reveal** – below-the-fold panels wait for IntersectionObserver; the 2.5s safety only reveals elements already in
  the viewport.
- **Reduced motion** – no tilt, parallax, floating/orbit/twinkle, hero glow drift, cube float, stagger or chart draw.

## Audit Logs, Notifications & Settings (Phase 5)

Same Aurora language: rounded surface cards, lavender/blue tints, layered shadows, the existing icon set, and
`[appReveal]` / `[appSpotlight]`.

- **Audit Logs:**
  - Filter bar: search, grouped action select, target select, pill tabs for outcome, and a UTC date range.
  - Table: time (relative, with the absolute time as a tooltip), actor avatar, action badge with a category icon
    (auth, users, people, settings), target label and type, outcome dot-badge, IP (mono), and a one-line details
    summary.
  - Rows stagger in (capped at 14). Clicking a row opens a right-hand drawer with an outcome-tinted header, a
    who/target/IP/request-ID list with a copy button, and the redacted details in words.
- **Notifications:**
  - Header bell: a gradient count badge that pops and rings once when the unread count rises (two alternating
    keyframes, so each rise replays exactly once).
  - Popover: the six newest, an unread dot, a hover mark-read button, mark all, and "View all".
  - `/notifications`: an All/Unread timeline grouped by day on a vertical rail. Items fade and slide out when marked
    read in the Unread view. The empty state is a floating bell orb.
- **Settings:**
  - Appearance card (with spotlight) with a sync-status dot.
  - Selectable preview cards: mode (split light/dark), five presets rendered as mini app windows in each preset's
    fixed colours, and density line previews. Cards lift on hover, press to 0.98, and pop in a check badge when
    selected.
  - Account card with identity and shortcuts (arrow shifts 3 px).
  - ADMIN-only Organization card with inline validation and optimistic-lock recovery ("Reload latest").
- **Density:** Comfortable (default) or Compact. Compact reduces table row padding (about 67 px → 55 px per row in
  practice), card and panel padding and the navigation row height (2.75 → 2.375 rem; the sliding pill follows).
- **Reduced motion:** bell ring and badge pop, empty-bell float, the sync pulse and row stagger all stop; the global
  reduced-motion rule makes transitions instant.

## Security & accessibility constraints (Phase 6)

- **No component `styles`/`styleUrl` and no static `style="…"` attributes.** The SPA's CSP is `style-src 'self'`
  (no `'unsafe-inline'`). Component styles live in the global stylesheet scoped by the host element
  (`styles/_component-styles.scss`, `styles/_login.scss`); variants use attributes or classes
  (e.g. `.icon-badge[data-tone='accent-2']`). `[style.x]` bindings remain fine (CSSOM). `npm run check:csp` enforces it.
- **Badge contrast:** badge text is mixed toward the theme's text colour
  (`color-mix(in srgb, var(--badge-color) 68%, var(--app-text))`) so every tone meets WCAG AA on its tint in light
  and dark themes; the tint and status dot keep the pure tone.
- **Class names must not collide across features:** the Settings appearance cards use `pref-*` classes (a generic
  `.choice` rule from Phase 5 had hidden the role checkboxes in the user dialog).
- **Scroll containers that hold screen-reader-only text must be `position: relative`**, or the absolutely positioned
  text escapes and widens the page on phones (the recent-hires table on the dashboard).
- Verified by the E2E suite: axe-core (WCAG 2.2 AA tags) on login and all pages in light and dark with no serious or
  critical findings; keyboard skip link, visible focus, dialog focus containment, Escape + focus return; reduced
  motion shows content immediately with no infinite animations; no horizontal overflow at 1440/1280/1024/768/390;
  popovers and drawers stay on screen at 390 px.

