package com.jellemax.detour.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/*
 * The two location grants the app tells apart (#500), in app/ because both
 * read the Context's permission state.
 *
 * Approximate is enough for everything that only needs to know roughly where
 * the rider is — the map, spin, search, the route editor, convoy. Recording a
 * trip is not: distance, speed and the trace are built from fixes accurate to
 * metres, so a trip only starts with precise location.
 */

/** Precise or approximate location — enough for the map, spin and search. */
fun hasLocationPermission(context: Context): Boolean =
    hasPreciseLocation(context) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** Precise location — what recording a trip needs. */
fun hasPreciseLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
