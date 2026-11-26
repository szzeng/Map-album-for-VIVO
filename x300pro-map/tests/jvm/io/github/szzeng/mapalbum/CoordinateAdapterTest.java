package io.github.szzeng.mapalbum;
import com.baidu.mapapi.model.LatLng;
public final class CoordinateAdapterTest {
 private static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  LatLng local=CoordinateAdapter.adapt(23,113);check(local.latitude==24&&local.longitude==114);
  LatLng abroad=CoordinateAdapter.adapt(48,2);check(abroad.latitude==48&&abroad.longitude==2);
  LatLng nearBorder=CoordinateAdapter.adapt(34,130);check(nearBorder.latitude==35&&nearBorder.longitude==131);
  System.out.println("CoordinateAdapterTest: passed (geographic test double)");
 }
}
