package io.github.szzeng.mapalbum;

import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.utils.CoordinateConverter;

/** WGS84 was best for the tested local samples; the provider's general CRS remains unverified. */
final class CoordinateAdapter {
    static LatLng adapt(double latitude,double longitude) {
        // A coarse geographic approximation, not a country boundary or proof of source CRS.
        // Nearby international border areas require manual coordinate validation.
        if(!likelyChinaRegion(latitude,longitude))return new LatLng(latitude,longitude);
        return new CoordinateConverter().from(CoordinateConverter.CoordType.GPS)
            .coord(new LatLng(latitude,longitude)).convert();
    }
    static boolean likelyChinaRegion(double latitude,double longitude){
        return latitude>=18 && latitude<=54 && longitude>=73 && longitude<=135;
    }
}
