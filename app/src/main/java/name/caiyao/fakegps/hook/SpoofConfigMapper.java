package name.caiyao.fakegps.hook;

import name.caiyao.fakegps.config.SpoofConfig;

/**
 * Converts a canonical {@link SpoofConfig} into the hook-layer {@link Snapshot} consumed
 * by {@link MainHook#CURRENT}, so the 20+ hook call-sites reading {@code CURRENT.get()}
 * do NOT change when the config channel moves from ContentProvider to JSON prefs.
 *
 * <p>Only fields already migrated into {@code SpoofConfig} (location / LTE cell / WiFi
 * core subset) are mapped; every other Snapshot field stays {@code null} = passthrough.
 * As more field groups migrate into {@code SpoofConfig}, extend THIS one method — it is
 * the single conversion point, not another source of truth.
 *
 * <p>Wiring status: this is the transport-to-hook adapter. It will be invoked by
 * {@code MainHook} once the XSharedPreferences read path lands (next slice, needs device
 * to verify the watcher). It is unit-tested here in isolation so the JSON→Snapshot logic
 * is locked before the runtime plumbing is attached.
 */
final class SpoofConfigMapper {

    private SpoofConfigMapper() {}

    static Snapshot toSnapshot(SpoofConfig config) {
        Snapshot s = new Snapshot();
        if (config == null) {
            return s; // all-null Snapshot == passthrough everything
        }

        SpoofConfig.Location loc = config.getLocation();
        if (loc != null) {
            s.latitude = loc.getLatitude();
            s.longitude = loc.getLongitude();
            s.altitude = loc.getAltitude();
            s.speed = loc.getSpeed();
            s.bearing = loc.getBearing();
            s.accuracy = loc.getAccuracy();
        }

        SpoofConfig.LteCell lte = config.getLteCell();
        if (lte != null) {
            s.tac = lte.getTac();
            s.ci = lte.getCi();
            s.pci = lte.getPci();
            s.earfcn = lte.getEarfcn();
            s.lteRsrp = lte.getRsrp();
            s.lteRsrq = lte.getRsrq();
            s.lteSinr = lte.getSinr();
        }

        SpoofConfig.Wifi wifi = config.getWifi();
        if (wifi != null) {
            s.wifiSsid = wifi.getSsid();
            s.wifiBssid = wifi.getBssid();
            s.wifiRssi = wifi.getRssi();
            s.wifiFrequency = wifi.getFrequency();
        }

        return s;
    }
}
