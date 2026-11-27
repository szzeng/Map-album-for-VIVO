package io.github.szzeng.mapalbum;

/** Version of the privacy explanation explicitly accepted by the user. */
final class PrivacyConsent {
    static final String KEY="baidu_privacy_consent_version";
    static final int VERSION=2;
    static boolean isCurrent(int acceptedVersion){return acceptedVersion==VERSION;}
    private PrivacyConsent(){}
}
