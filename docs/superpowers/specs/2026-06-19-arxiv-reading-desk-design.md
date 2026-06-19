# PaperLoom Journal Ink UI Design

## Goal

Re-skin the PaiSmart frontend as PaperLoom, a journal-like research workspace for evidence-grounded paper RAG workflows.

## Direction

Use a Journal Ink visual language: 纸灰页面底、近白论文纸卡片、深墨蓝 primary、暗酒红 citation/evidence accents、serif display headings、compact evidence-oriented panels, and document/library semantics. The interface should feel like a scholarly retrieval and source-verification workbench rather than a generic enterprise assistant or a direct clone of any public archive site.

## Scope

- Update global theme tokens to Journal Ink colors: `#26364a` primary, `#7e3f46` evidence accent, `#eeeae1` layout, `#fbfaf6` paper surface, and `#c9c1b2` binding lines.
- Replace the image-based product logo with an inline woven paper PaperLoom mark.
- Rename visible product copy to PaperLoom where it appears in the main frontend workflow.
- Change route/menu icons to document, library, experiment, classification, collaborator, and usage semantics.
- Re-style chat shell, conversation sidebar, welcome state, input box, chat messages, source references, knowledge-base table shell, admin console pages, and file preview panel.
- Preserve existing chat/session/reference-preview behavior and backend API contracts.

## Out of Scope

- Backend or RAG pipeline changes.
- Replacing Naive UI or the existing Vue component structure.
- Large rewrites of admin page behavior or backend management APIs.

## Validation

- Run frontend type checking or a targeted equivalent if unrelated local test files block the repo command.
- Verify `http://localhost:9527/#/chat`, `http://localhost:9527/#/login`, and representative admin pages in the browser.
- Confirm no text overlap in the main chat, sidebar, source list, login card, library table header, and admin console headers.
