# Kin-Keeper Roadmap

Running list of deferred features and decisions. "Shipped" stays here briefly as
context for what a section replaced; once obvious from the code it gets removed.

## Plans — map view (deferred)

**Status:** deferred at the v1 plans cut.

The Plans feature (see `Plan` / `PlanSegment` models, `PlansPage`, and the
`list_plans` / `create_plan` / `add_plan_segment` / `link_document_to_plan` agent
tools) ships as a list-only itinerary builder. Wanderlog's signature feature —
markers on a map, day-by-day routing, drag-to-reorder across a geographic view —
is **not** built yet.

### Why it's deferred
- Separate dependency (Google Maps JS API or Leaflet + tile provider) with its own
  key management, rate limits, and (for Google) billing.
- Each `PlanSegment` would need lat/lng fields plus geocoding on save or a
  just-in-time geocode at render. Geocoding is another paid API call.
- Per-day routing / polylines and marker clustering are a meaningful chunk of
  frontend work that doesn't land in an afternoon.

### What to build when we pick it up
1. Extend `PlanSegment` with `latitude: Double`, `longitude: Double`, `placeId: String`
   (optional, for Google). Keep `location` as the free-text fallback.
2. On segment create/update, call a geocoding service to populate lat/lng when
   the user sets `location`. Cache geocodes in Redis — user-provided place names
   don't change between sessions.
3. Frontend: add a map panel to `PlanDetail`. For Google Maps, load `@googlemaps/js-api-loader`
   lazily so the Plans page doesn't pay the bundle cost on every visit. For
   Leaflet, pick a tile provider (OpenStreetMap works for personal-scale use).
4. Group markers by day if the plan spans multiple days; draw light polylines
   between same-day stops ordered by `startAt`.
5. Optional: click a marker → jump to the segment in the list and vice versa.

### Non-goals (still)
- Turn-by-turn routing inside Kin-Keeper. Hand off to Google Maps / Apple Maps
  via a deep link instead — we aren't building a navigation app.
- Custom map drawing / trip planning UX (Wanderlog's "save a place to visit"
  search panel). Users can add unstructured activities via `ACTIVITY` segments.

### Rough cost
~1–2 days for a Leaflet-based v1 (no Google billing), ~0.5 day more for Google
Maps if we decide the satellite imagery / place search is worth the setup.
