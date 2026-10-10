# BRouter engine (vendored)

The routing engine of [BRouter](https://github.com/abrensch/brouter) **1.7.10** (MIT licence, see `LICENSE`): the
`brouter-util`, `brouter-codec`, `brouter-expressions`, `brouter-mapaccess` and `brouter-core` modules, copied
unmodified into one Java library. AMRI Maps (`app/.../nav/offline/OfflineRouter.kt`) runs it on the unit to route
without internet over BRouter's routing data (`segments4/*.rd5`, built weekly from OpenStreetMap, downloaded from
brouter.de by the offline-map download), with the `car-vario` profile and `lookups.dat` of the same release
(`app/src/main/assets/offline/profiles2/`).

To update: take the same five modules of a newer release and replace `src/main/java`, together with the profile files
(the segment format must match the engine: `segments4`).
