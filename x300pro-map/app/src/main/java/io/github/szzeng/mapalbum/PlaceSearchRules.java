package io.github.szzeng.mapalbum;

final class PlaceSearchRules {
    enum Route { DIRECT, DETAIL, KEYWORD }
    static Route route(boolean validPoint,String uid){
        if(validPoint)return Route.DIRECT;
        return uid!=null&&!uid.trim().isEmpty()?Route.DETAIL:Route.KEYWORD;
    }
    static boolean validCoordinate(double latitude,double longitude){
        return Double.isFinite(latitude)&&Double.isFinite(longitude)&&Math.abs(latitude)<=90&&Math.abs(longitude)<=180&&(latitude!=0||longitude!=0);
    }
}
