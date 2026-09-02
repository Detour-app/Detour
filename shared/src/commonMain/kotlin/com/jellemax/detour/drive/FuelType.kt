package com.jellemax.detour.drive

/** A vehicle's fuel, for the MAF-derived fuel-rate estimate. A diesel is
 *  denser and stoichiometric at a slightly different ratio than petrol, and —
 *  the bigger effect — runs lean, which [Obd2Pids.fuelRateFromMafLph] accounts
 *  for via the commanded-lambda PID (0144) when the adapter reports it. The
 *  direct fuel-rate PID (015E) needs none of this; the ECU already knows. */
enum class FuelType { PETROL, DIESEL }
