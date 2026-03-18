package com.odc.syncwrapper;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FileNamingStrategy {

  public String createUniqueFilename(String originalFilename) {
    String uuid = UUID.randomUUID().toString();
    if (originalFilename == null || originalFilename.isEmpty()) {
      return uuid;
    }

    int lastDotIndex = originalFilename.lastIndexOf('.');
    if (lastDotIndex == -1) {
      return originalFilename + "_" + uuid;
    } else {
      String nameWithoutExtension = originalFilename.substring(0, lastDotIndex);
      String extension = originalFilename.substring(lastDotIndex);
      return nameWithoutExtension + "_" + uuid + extension;
    }
  }
}
