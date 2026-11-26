package com.baidu.mapapi.utils;
import com.baidu.mapapi.model.LatLng;
public final class CoordinateConverter { public enum CoordType { GPS } private LatLng point; public CoordinateConverter from(CoordType type){return this;} public CoordinateConverter coord(LatLng point){this.point=point;return this;} public LatLng convert(){return new LatLng(point.latitude+1,point.longitude+1);} }
