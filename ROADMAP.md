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

## Document ingestion — email forwarding (deferred)

**Status:** deferred; OCR-on-upload shipped, email forwarding did not.

Paperless-ngx has an IMAP consumer that polls a mailbox and auto-ingests every
attachment. The equivalent here would be: receive email at an address like
`bills@<your-domain>` and have the backend pick up attachments, run the same
Claude-vision extraction + classification we already do, and file the result
under POLICY (or whatever the contents suggest).

### Why it's deferred
- Needs an inbound mail route (either host an MTA on the homelab, or use a
  managed webhook like Cloudflare Email Routing / Postmark's inbound / SES
  inbound). Each has its own DKIM/SPF setup.
- Per-family addressing (`<familySlug>@kin-keeper.local`) adds another config
  moving part for what is currently a single-family install.

### What to build when we pick it up
1. Decide transport: Cloudflare Email Worker → POST to a new `/api/inbound/email`
   endpoint is probably the least setup for a homelab.
2. On receipt: parse attachments (MIME-decode), resolve family from the
   To-address, stage each attachment the way the chat flow does, and kick off
   the existing classify-and-save chat prompt. User sees the result land on
   the Documents page with a chat session showing what the agent did.
3. Keep a dedupe window on Message-ID so a forwarded email doesn't create
   two copies.

### Non-goals
- Full IMAP polling. If you're already reading mail, forwarding the relevant
  ones is fine; we don't need to host a mail client.
- Outbound email. Kin-Keeper reads, never sends.

## PWA Share Target — file sharing via POST (deferred)

**Status:** text sharing (method: GET) shipped via `vite.config.ts`
`share_target` + the `/share` route. File sharing (PDFs, images from a share
sheet) is not wired yet.

### Why it's deferred
Files require `method: POST` with `enctype: multipart/form-data`, which React
Router can't serve directly — the browser POSTs the share to our origin and
expects a response. We need a service worker that intercepts the POST,
stashes the files in Cache Storage, and redirects the page to `/share?key=…`
where the React route can pull them out and feed them to the staging endpoint.

Our current PWA uses `generateSW` mode, which doesn't expose a fetch hook for
custom POST handlers. Implementing file sharing means switching to
`injectManifest` mode and writing a small `sw.ts` alongside the Vite build.

### What to build when we pick it up
1. Flip `VitePWA({ strategies: 'injectManifest', srcDir: 'src', filename: 'sw.ts' })`.
2. In `sw.ts`: `precacheAndRoute(self.__WB_MANIFEST)` for the app shell, plus
   a `self.addEventListener('fetch', …)` that matches `POST /share`, reads
   `await event.request.formData()`, writes each `File` into a dedicated
   `Cache` under `/__shared/<key>/<i>`, then
   `return Response.redirect('/share?key=<key>', 303)`.
3. Update `SharePage.tsx` to recognise `?key=…`, fetch each cached file back
   as a `Blob`, wrap as `File`, and feed into the existing
   `POST /api/chat/attachments` + new-session flow.
4. Add `files: [{ name: 'file', accept: [...] }]` to the manifest's
   `share_target.params`.

### Non-goals
- Background ingestion (receiving a share while the app isn't open and
  processing it silently). Web share target always opens the app to the
  configured action URL — that's the platform contract.
