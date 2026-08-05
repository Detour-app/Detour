// Keeps custom:map-card's entities pointed at the current hass.
//
// The card builds an `Entity` per configured entity and stores the hass object
// it was constructed with; nothing ever hands those objects a newer one
// (EntitiesRenderService.render() only calls entity.update()). Home Assistant
// replaces the hass object on every state change rather than mutating it, so
// every position and attribute the card reads through an Entity is frozen at
// card-construction time. Two consequences on the Detour dashboard:
// picking another ride left the map framed on the previous one, and any
// entity-attribute GeoJSON never changed at all.
//
// This patches MapCard.prototype.render to refresh those references first. It
// is deliberately defensive: if upstream renames any of this, the patch turns
// into a no-op instead of breaking the card. Loaded as its own Lovelace
// resource so HACS can keep updating ha-map-card itself.
//
// Upstream: nathan-gs/ha-map-card (v1.15.0 when this was written).

customElements.whenDefined("map-card").then(() => {
  const MapCard = customElements.get("map-card");
  if (!MapCard || MapCard.__hassRefreshPatched) return;

  const original = MapCard.prototype.render;
  if (typeof original !== "function") return;

  MapCard.prototype.render = function () {
    try {
      const hass = this.hass;
      if (hass) {
        this.entitiesRenderService?.entities?.forEach((entity) => {
          if (entity) entity.hass = hass;
        });
        if (this.entitiesRenderService) this.entitiesRenderService.hass = hass;
        if (this.initialViewRenderService) this.initialViewRenderService.hass = hass;

        // Refit when the tracked entities actually move — which on this
        // dashboard means a different ride was picked. `focus_follow: contains`
        // only refits when the new shape falls outside the view, so switching
        // from a 400 km ride to a 5 km one left the map zoomed out over it.
        // Keyed on the positions themselves, so panning and zooming around one
        // ride never triggers it: the card would fight the user otherwise.
        const fitEntities = (this.entitiesRenderService?.entities || [])
          .filter((e) => e?.config?.focusOnFit);
        if (fitEntities.length) {
          const signature = fitEntities
            .map((e) => { try { const p = e.latLng; return p.lat + "," + p.lng; }
                          catch (err) { return "?"; } })
            .join("|");
          if (this.__fitSignature && this.__fitSignature !== signature) {
            this.entitiesRenderService.setInitialView();
          }
          this.__fitSignature = signature;
        }
      }
    } catch (e) {
      console.warn("[map-card-hass-refresh] skipped:", e);
    }
    return original.apply(this, arguments);
  };

  MapCard.__hassRefreshPatched = true;
  console.info("%cmap-card-hass-refresh: entities follow hass",
    "color: #1baf7a; font-weight: bold");
});
