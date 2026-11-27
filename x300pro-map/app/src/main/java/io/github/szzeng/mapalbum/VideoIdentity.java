package io.github.szzeng.mapalbum;

/** Fail closed when the two providers cannot establish the same video record. */
public final class VideoIdentity {
    private VideoIdentity() { }
    public static boolean matches(Integer providerType,String mediaPath,String providerPath){
        return providerType!=null&&providerType==3&&mediaPath!=null&&!mediaPath.isEmpty()&&mediaPath.equals(providerPath);
    }
}
