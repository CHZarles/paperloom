# Model Provider UI Polish Design

## Goal

Align the outer card of the PaperLoom model-provider admin page (`/#/model-provider`) with the Journal Ink vintage card style used on the knowledge-base page, and restyle the existing in-card note callout so it doesn't visually clash with the new card. No logic changes, no backend changes, no new files. Only the outer NCard and the note inside it are touched; `.provider-scope` and `.provider-card` (LLM/Embedding section cards) are out of scope.

## Direction

The outer `NCard` adopts the exact style of the knowledge-base `.paper-library-card` (10px radius, softer 5px/5px/0/0.42 offset shadow, `#e2dccc` header band, Georgia serif 22px title with `letter-spacing: 0.2px`, 16px 20px content padding, 14px 20px header padding). The note callout below the header switches from a hard-tan block to a "dashed paper sticker" feel (white paper background, dashed binding-line border, 8px radius). Together these two changes make the page's outer chrome match the rest of the app while letting the inner admin form structure (which is functional, not decorative) remain as it is.

## Scope

### File

`frontend/src/views/model-provider/index.vue` — single file, two small template edits + one scoped style block at the top of the existing `<style scoped lang="scss">`.

### Template Edits

1. **Outer NCard class — add `model-provider-card`:**
   ```html
   <NCard :bordered="false" size="small" class="admin-console-card card-wrapper model-provider-card">
   ```
   (Existing `admin-console-card card-wrapper` classes stay; the new class is additive.)

2. **Header-extra text — drop `text-stone-400`:**
   ```html
   <span class="text-xs">LLM 保存后新请求立即生效，Embedding 暂不允许危险直切</span>
   ```
   The `text-stone-400` utility puts text on a near-white tone that has poor contrast on the new `#e2dccc` band. Color is now driven by `.model-provider-card ::v-deep(.n-card-header__extra) { color: #5e6470; }` (defined below).

3. **Note container — add `model-provider-note`:**
   ```html
   <div class="admin-console-note mb-4 model-provider-note">
   ```
   The existing `admin-console-note mb-4` classes stay for semantics. The new class enables the local override.

### Scoped Style Additions

Insert at the top of `<style scoped lang="scss">`:

```scss
// 1. Outer card: align with knowledge-base .paper-library-card
.model-provider-card {
  border-radius: 10px !important;
  box-shadow: 5px 5px 0 rgba(201, 193, 178, 0.42) !important;
}

.model-provider-card ::v-deep(.n-card-header) {
  border-bottom: 1px solid #c9c1b2;
  background: #e2dccc;
  padding: 14px 20px;
}

.model-provider-card ::v-deep(.n-card-header__main) {
  color: #26364a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.2px;
}

.model-provider-card ::v-deep(.n-card-header__extra) {
  color: #5e6470;
}

.model-provider-card ::v-deep(.n-card__content) {
  background: #fbfaf6;
  padding: 16px 20px;
}

// 2. Note: dashed paper sticker
.model-provider-note {
  background: #fbfaf6 !important;
  border: 1px dashed #c9c1b2 !important;
  border-radius: 8px;
  font-size: 13px;
}
```

### Why `!important`

The global `.admin-console-card` rule in `frontend/src/styles/css/global.css` uses `!important` for `border`, `border-radius`, and `background`. To override those specific values from a scoped style on this page, the new values for `border-radius` and `box-shadow` also need `!important`. We do **not** touch `global.css` because `.admin-console-card` is shared with the user page and the existing user-page layout is intentionally untouched.

## Out of Scope

- `.provider-scope` (LLM / Embedding section container styling)
- `.provider-card` (individual provider card styling)
- Any JS, API, or form-input logic
- `frontend/src/styles/css/global.css` (no global changes; `admin-console-card` and `admin-console-note` stay as defined for other pages)
- Responsive breakpoints

## Validation

- `cd frontend && pnpm typecheck` → exit 0
- Open `http://localhost:9527/#/model-provider` in the browser
- Confirm: outer card now has 10px corners, softer shadow, tan header band, Georgia serif 22px title, balanced padding
- Confirm: in-card note is a white-background, dashed-border, 8px-radius "sticker" — visually lighter than before, harmonious with the new card
- Confirm: `.provider-scope` and `.provider-card` sections look unchanged
- Confirm: functionality (active provider select, save button, form inputs, test connection) is unaffected