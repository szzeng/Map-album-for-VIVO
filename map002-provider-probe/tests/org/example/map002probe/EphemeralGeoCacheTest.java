package org.example.map002probe;

public final class EphemeralGeoCacheTest {
    private static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        EphemeralGeoCache cache=new EphemeralGeoCache(2);
        EphemeralGeoCache.Key a=new EphemeralGeoCache.Key("primary",1),b=new EphemeralGeoCache.Key("other",1),c=new EphemeralGeoCache.Key("primary",2);
        EphemeralGeoCache.Validator allow=new EphemeralGeoCache.Validator(){ public boolean currentlyAuthorizedAndIdentityVerified(String volume,long id){return true;} };
        EphemeralGeoCache.Validator deny=new EphemeralGeoCache.Validator(){ public boolean currentlyAuthorizedAndIdentityVerified(String volume,long id){return false;} };
        EphemeralGeoCache.Validator failed=new EphemeralGeoCache.Validator(){ public boolean currentlyAuthorizedAndIdentityVerified(String volume,long id){throw new SecurityException();} };
        cache.put(a,1,2);cache.put(b,3,4);check(cache.getVerified(a,allow)!=null);cache.put(c,5,6);
        check(cache.size()==2 && cache.getVerified(b,allow)==null && cache.getVerified(a,allow)!=null);
        check(cache.getVerified(a,deny)==null && cache.getVerified(a,allow)==null);
        check(cache.getVerified(c,failed)==null && cache.size()==0);
        cache.put(a,1,2);cache.clear();check(cache.size()==0 && cache.getVerified(a,allow)==null);
        try {cache.put(a,Double.NaN,0);throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("ephemeral-cache-tests: passed");
    }
}
