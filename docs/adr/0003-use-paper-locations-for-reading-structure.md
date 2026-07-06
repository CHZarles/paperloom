# Use paper locations for reading structure

PaperLoom will model page, section, table, and figure navigation through `paper_locations` instead
of letting parser ids, table ids, figure ids, or section titles become separate reading coordinates.
Typed content remains in `PaperPage`, `PaperSection`, `PaperTable`, and `PaperFigure`, while
`PaperLocation.locationRef` is the opaque coordinate used by listing, search, reading, and later
Source Quote creation.

**Considered Options**

- Expose `PaperTable.tableId`, `PaperFigure.figureId`, and section titles directly to future tools.
  Rejected because it creates several coordinate systems and makes Source Quote creation depend on
  parser-specific identities.
- Keep only PAGE locations and let retrieval chunks point at tables/figures directly. Rejected
  because chunk hits would need to be reverse-mapped into read coordinates before citation, making
  `read_locations` less deterministic.

**Consequences**

- Reading Model rebuild may create new page, section, table, and figure refs; old refs are metadata
  unless they have already produced persisted Source Quotes.
- SECTION needs a small persisted `PaperSection` content model because a section ref must be
  readable without reopening MinerU artifacts.
- TABLE and FIGURE locations can point at existing structured stores by internal source object id,
  but future LLM-facing tools must return only opaque `locationRef` values.
