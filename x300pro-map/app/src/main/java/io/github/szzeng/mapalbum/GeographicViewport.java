package io.github.szzeng.mapalbum;

/** Geographic bounds. Use the center to distinguish a date-line crossing from a wide view. */
final class GeographicViewport {
    static ViewportBounds fromCorners(double[] latitudes,double[] longitudes,double centerLongitude,double padding) {
        if(latitudes==null||longitudes==null||latitudes.length==0||latitudes.length!=longitudes.length||!Double.isFinite(padding)||padding<0||!Double.isFinite(centerLongitude)||centerLongitude< -180||centerLongitude>180)throw new IllegalArgumentException();
        double south=90,north=-90,low=180,high=-180;
        for(int i=0;i<latitudes.length;i++){
            double lat=latitudes[i],lon=longitudes[i];
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat< -90||lat>90||lon< -180||lon>180)throw new IllegalArgumentException();
            south=Math.min(south,lat);north=Math.max(north,lat);low=Math.min(low,lon);high=Math.max(high,lon);
        }
        south=Math.max(-90,south-padding);north=Math.min(90,north+padding);
        double west=low,east=high;
        if(high-low>180 && (centerLongitude<low || centerLongitude>high)){west=high;east=low;}
        else if(high-low>180){return new ViewportBounds(south,north,-180,180);}
        double span=west<=east?east-west:360-west+east;
        if(span+2*padding>=360)return new ViewportBounds(south,north,-180,180);
        west-=padding;east+=padding;
        if(west< -180)west+=360;if(east>180)east-=360;
        return new ViewportBounds(south,north,west,east);
    }
    private GeographicViewport(){}
}
