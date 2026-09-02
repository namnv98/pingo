package com.lego.namnv.core.message.file;

import java.io.File;
import java.nio.charset.Charset;

public interface LegoFileUpload {

    String getName();

    String getOriginalFileName();

    String getUploadedFileName();

    long getSize();

    Charset getCharset();

    String getContentTransferEncoding();

    String getContentType();

    File getUploadedFile();

    String getFilePath();

    String getAbsolutePath();

}

