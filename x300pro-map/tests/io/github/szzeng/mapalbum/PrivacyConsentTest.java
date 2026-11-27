package io.github.szzeng.mapalbum;
public final class PrivacyConsentTest {
 public static void main(String[] args){
  if(PrivacyConsent.isCurrent(0)||!PrivacyConsent.isCurrent(PrivacyConsent.VERSION)||PrivacyConsent.isCurrent(PrivacyConsent.VERSION+1))throw new AssertionError();
  System.out.println("PrivacyConsentTest: passed");
 }
}
