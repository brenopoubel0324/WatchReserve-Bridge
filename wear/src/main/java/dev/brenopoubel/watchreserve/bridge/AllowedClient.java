package dev.brenopoubel.watchreserve.bridge;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.security.MessageDigest;

final class AllowedClient {
    static final String PACKAGE = "dev.brenopoubel.watchreserve.advancedlab";
    // Cliente de desenvolvimento qualificado nesta etapa; não é o app de usuário.
    private static final String CERT = "caed7e1e5f17eead9720810079e0072264358d3219d72f67a0ea19466ec48d69";

    static int uid(PackageManager pm) throws Exception {
        PackageInfo info = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        int uid = info.applicationInfo.uid;
        String[] siblings = pm.getPackagesForUid(uid);
        if (siblings == null || siblings.length != 1 || !PACKAGE.equals(siblings[0])) {
            throw new SecurityException("UID compartilhado ou cliente ausente.");
        }
        if (info.signingInfo.hasMultipleSigners()) throw new SecurityException("Múltiplas assinaturas.");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
        StringBuilder digest = new StringBuilder();
        for (byte value : hash) digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!CERT.contentEquals(digest)) throw new SecurityException("Assinatura do laboratório não qualificada.");
        if (info.getLongVersionCode() != 2 || !"0.2-readonly-sessions".equals(info.versionName)) {
            throw new SecurityException("Versão do laboratório não qualificada.");
        }
        return uid;
    }
}
