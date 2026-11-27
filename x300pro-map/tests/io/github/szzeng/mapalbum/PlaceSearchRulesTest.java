package io.github.szzeng.mapalbum;
public class PlaceSearchRulesTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        check(PlaceSearchRules.route(true,"uid")==PlaceSearchRules.Route.DIRECT);
        check(PlaceSearchRules.route(true,null)==PlaceSearchRules.Route.DIRECT);
        check(PlaceSearchRules.route(false,"uid")==PlaceSearchRules.Route.DETAIL);
        check(PlaceSearchRules.route(false," ")==PlaceSearchRules.Route.KEYWORD);
        check(PlaceSearchRules.route(false,null)==PlaceSearchRules.Route.KEYWORD);
        check(PlaceSearchRules.validCoordinate(30.24,120.15));
        check(!PlaceSearchRules.validCoordinate(0,0));
        check(!PlaceSearchRules.validCoordinate(Double.NaN,120));
        check(!PlaceSearchRules.validCoordinate(91,120));
        System.out.println("PlaceSearchRulesTest passed");
    }
}
