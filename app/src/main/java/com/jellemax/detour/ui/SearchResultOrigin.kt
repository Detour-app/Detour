package com.jellemax.detour.ui

/** Where a listed search hit came from, which picks its row's icon in [SearchResultRow]. */
enum class SearchResultOrigin {
    /** One of the rider's saved places (#1007): a bookmark. */
    SAVED,

    /** A recent pick: history. */
    RECENT,

    /** A live geocoder hit: its kind's own icon. */
    LIVE,
}
