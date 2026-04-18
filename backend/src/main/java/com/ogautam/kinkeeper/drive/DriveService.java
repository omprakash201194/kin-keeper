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
        Drive drive = driveFor(adminUid);
        String rootFolderId = getOrCreateRootFolder(adminUid);

        File metadata = new File()
                .setName(fileName)
                .setParents(Collections.singletonList(rootFolderId));
        InputStreamContent mediaContent = new InputStreamContent(mimeType, content);

        File uploaded = drive.files().create(metadata, mediaContent)
                .setFields("id, name, size, mimeType")
                .execute();
        log.info("Uploaded '{}' ({}) to Drive file {}", fileName, mimeType, uploaded.getId());
        return uploaded.getId();
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
