package com.ogautam.kinkeeper.drive;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class DriveService {

    private static final String APPLICATION_NAME = "kin-keeper";
    private static final String ROOT_FOLDER_NAME = "Kin-Keeper";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    private final GoogleOAuthProperties props;
    private final UserService userService;

    public DriveService(GoogleOAuthProperties props, UserService userService) {
        this.props = props;
        this.userService = userService;
    }

    public String getOrCreateRootFolder(String adminUid) throws IOException, GeneralSecurityException, ExecutionException, InterruptedException {
        Drive drive = driveFor(adminUid);
        FileList result = drive.files().list()
                .setQ("name = '" + ROOT_FOLDER_NAME + "' and mimeType = '" + FOLDER_MIME + "' and trashed = false")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute();

        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId();
        }

        File metadata = new File()
                .setName(ROOT_FOLDER_NAME)
                .setMimeType(FOLDER_MIME);
        File created = drive.files().create(metadata).setFields("id").execute();
        log.info("Created Drive root folder {} for admin {}", created.getId(), adminUid);
        return created.getId();
    }

    public String uploadFile(String adminUid, String fileName, String mimeType, InputStream content)
            throws IOException, GeneralSecurityException, ExecutionException, InterruptedException {
        return uploadFile(adminUid, fileName, mimeType, content, null);
    }

    /**
     * Upload into a nested folder under the Kin-Keeper root. Each segment is created
     * on demand and reused afterwards, so the Drive tree ends up as e.g.
     *   Kin-Keeper/Omprakash Gautam/Education/file.pdf
     * instead of dumping every document into one flat folder.
     */
    public String uploadFile(String adminUid, String fileName, String mimeType, InputStream content,
                             List<String> pathSegments)
            throws IOException, GeneralSecurityException, ExecutionException, InterruptedException {
        Drive drive = driveFor(adminUid);
        String parentId = ensureFolderPath(adminUid, pathSegments);

        File metadata = new File()
                .setName(fileName)
                .setParents(Collections.singletonList(parentId));
        InputStreamContent mediaContent = new InputStreamContent(mimeType, content);

        File uploaded = drive.files().create(metadata, mediaContent)
                .setFields("id, name, size, mimeType")
                .execute();
        log.info("Uploaded '{}' ({}) to Drive file {} under folder {}", fileName, mimeType, uploaded.getId(), parentId);
        return uploaded.getId();
    }

    public String ensureFolderPath(String adminUid, List<String> pathSegments)
            throws IOException, GeneralSecurityException, ExecutionException, InterruptedException {
        String currentParent = getOrCreateRootFolder(adminUid);
        if (pathSegments == null) return currentParent;
        Drive drive = driveFor(adminUid);
        for (String raw : pathSegments) {
            String name = sanitizeFolderName(raw);
            if (name == null) continue;
            currentParent = findOrCreateChildFolder(drive, currentParent, name);
        }
        return currentParent;
    }

    private String findOrCreateChildFolder(Drive drive, String parentId, String name) throws IOException {
        String escapedName = name.replace("\\", "\\\\").replace("'", "\\'");
        String query = String.format(
                "'%s' in parents and name = '%s' and mimeType = '%s' and trashed = false",
                parentId, escapedName, FOLDER_MIME);
        FileList result = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute();
        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId();
        }
        File metadata = new File()
                .setName(name)
                .setMimeType(FOLDER_MIME)
                .setParents(Collections.singletonList(parentId));
        File created = drive.files().create(metadata).setFields("id").execute();
        log.info("Created Drive subfolder '{}' ({}) under parent {}", name, created.getId(), parentId);
        return created.getId();
    }

    private static String sanitizeFolderName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        // reason: Drive allows nearly anything, but path-like chars in a name look weird
        // in the Drive UI and make manual navigation confusing. Normalise whitespace and
        // strip slashes/backslashes.
        String cleaned = trimmed.replaceAll("[/\\\\]", "-").replaceAll("\\s+", " ");
        // Cap length defensively — Drive allows 32KB but 120 is readable.
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    public byte[] downloadFile(String adminUid, String driveFileId)
            throws IOException, GeneralSecurityException, ExecutionException, InterruptedException {
        Drive drive = driveFor(adminUid);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        drive.files().get(driveFileId).executeMediaAndDownloadTo(out);
        return out.toByteArray();
    }

    public void deleteFile(String adminUid, String driveFileId)
            throws IOException, GeneralSecurityException, ExecutionException, InterruptedException {
        Drive drive = driveFor(adminUid);
        drive.files().delete(driveFileId).execute();
        log.info("Deleted Drive file {}", driveFileId);
    }

    public File getFileMetadata(String adminUid, String driveFileId)
            throws IOException, GeneralSecurityException, ExecutionException, InterruptedException {
        Drive drive = driveFor(adminUid);
        return drive.files().get(driveFileId).setFields("id, name, size, mimeType, createdTime").execute();
    }

    private Drive driveFor(String adminUid) throws GeneralSecurityException, IOException, ExecutionException, InterruptedException {
        String refreshToken = userService.getDriveRefreshToken(adminUid);
        if (refreshToken == null) {
            throw new IllegalStateException("User " + adminUid + " has not connected Google Drive");
        }
        GoogleCredentials credentials = UserCredentials.newBuilder()
                .setClientId(props.getClientId())
                .setClientSecret(props.getClientSecret())
                .setRefreshToken(refreshToken)
                .build()
                .createScoped(Collections.singleton(DriveScopes.DRIVE_FILE));

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
