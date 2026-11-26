package io.github.szzeng.mapalbum;

/** Geographic bounds; west > east denotes a view crossing the date line. */
final class ViewportBounds {
    final double south, north, west, east;
    ViewportBounds(double south,double north,double west,double east){this.south=south;this.north=north;this.west=west;this.east=east;}
    boolean covers(double south,double north,double west,double east){
        if(south>north||south<this.south||north>this.north)return false;
        if(this.west<=this.east){
            return west<=east && west>=this.west && east<=this.east;
        }
        if(west<=east)return (west>=this.west && east<=180)||(west>=-180 && east<=this.east);
        return west>=this.west && east<=this.east;
    }
}
