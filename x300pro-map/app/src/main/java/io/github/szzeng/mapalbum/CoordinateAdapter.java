package io.github.szzeng.mapalbum;

import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.utils.CoordinateConverter;

/** WGS84 was best for the tested local samples; the provider's general CRS remains unverified. */
final class CoordinateAdapter {
    static LatLng adapt(double latitude,double longitude) {
        return new CoordinateConverter().from(CoordinateConverter.CoordType.GPS)
            .coord(new LatLng(latitude,longitude)).convert();
    }
}
