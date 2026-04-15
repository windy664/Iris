package art.arcane.iris.core.runtime;

import com.google.gson.GsonBuilder;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import lombok.Data;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Data
public final class DatapackReadinessResult {
    private final String requestedPackKey;
    private final List<String> resolvedDatapackFolders;
    private final String externalDatapackInstallResult;
    private final boolean verificationPassed;
    private final List<String> verifiedPaths;
    private final List<String> missingPaths;
    private final boolean restartRequired;

    public String toJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(this);
    }

    public static DatapackReadinessResult installForStudioWorld(
            String requestedPackKey,
            String dimensionTypeKey,
            File worldFolder,
            boolean verifyDataPacks,
            boolean includeExternalDataPacks,
            KMap<String, KList<File>> extraWorldDatapackFoldersByPack
    ) {
        ArrayList<String> resolvedFolders = new ArrayList<>();
        File datapacksFolder = ServerConfigurator.resolveDatapacksFolder(worldFolder);
        resolvedFolders.add(datapacksFolder.getAbsolutePath());
        if (extraWorldDatapackFoldersByPack != null) {
            KList<File> extraFolders = extraWorldDatapackFoldersByPack.get(requestedPackKey);
            if (extraFolders != null) {
                for (File extraFolder : extraFolders) {
                    if (extraFolder == null) {
                        continue;
                    }
                    String path = extraFolder.getAbsolutePath();
                    if (!resolvedFolders.contains(path)) {
                        resolvedFolders.add(path);
                    }
                }
            }
        }

        String externalResult = "ok";
        boolean restartRequired = false;
        try {
            restartRequired = ServerConfigurator.installDataPacks(verifyDataPacks, includeExternalDataPacks, extraWorldDatapackFoldersByPack);
        } catch (Throwable e) {
            externalResult = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        }

        ArrayList<String> verifiedPaths = new ArrayList<>();
        ArrayList<String> missingPaths = new ArrayList<>();
        String verificationDimensionTypeKey = (dimensionTypeKey == null || dimensionTypeKey.isBlank())
                ? requestedPackKey
                : dimensionTypeKey;
        for (String folderPath : resolvedFolders) {
            File folder = new File(folderPath);
            collectVerificationPaths(folder, verificationDimensionTypeKey, verifiedPaths, missingPaths);
        }

        boolean verificationPassed = missingPaths.isEmpty() && "ok".equals(externalResult);
        return new DatapackReadinessResult(
                requestedPackKey,
                List.copyOf(resolvedFolders),
                externalResult,
                verificationPassed,
                List.copyOf(verifiedPaths),
                List.copyOf(missingPaths),
                restartRequired
        );
    }

    static void collectVerificationPaths(File folder, String dimensionTypeKey, List<String> verifiedPaths, List<String> missingPaths) {
        File packMeta = new File(folder, "iris/pack.mcmeta");
        File dimensionType = new File(folder, "iris/data/iris/dimension_type/" + dimensionTypeKey + ".json");
        if (packMeta.exists()) {
            verifiedPaths.add(packMeta.getAbsolutePath());
        } else {
            missingPaths.add(packMeta.getAbsolutePath());
        }
        if (dimensionType.exists()) {
            verifiedPaths.add(dimensionType.getAbsolutePath());
        } else {
            missingPaths.add(dimensionType.getAbsolutePath());
        }
    }
}
