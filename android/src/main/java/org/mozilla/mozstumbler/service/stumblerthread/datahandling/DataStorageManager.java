/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.mozstumbler.service.stumblerthread.datahandling;

import android.content.Context;

import org.mozilla.mozstumbler.service.AppGlobals;
import org.mozilla.mozstumbler.service.core.logging.Log;
import org.mozilla.mozstumbler.service.utils.Zipper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

/* Stores reports in memory (mCurrentReports) until MAX_REPORTS_IN_MEMORY,
 * then writes them to disk as a .gz file. The name of the file has
 * the time written, the # of reports, and the # of cells and wifis.
 *
 * Each .gz file is typically 1-5KB. File name example: reports-t1406863343313-r4-w25-c7.gz
 *
 * The sync stats are written as a key-value pair file (not zipped).
 *
 * The tricky bit is the mCurrentReportsSendBuffer. When the uploader code begins accessing the
 * report batches, mCurrentReports gets pushed to mCurrentReportsSendBuffer.
 * The mCurrentReports is then cleared, and can continue receiving new reports.
 * From the uploader perspective, mCurrentReportsSendBuffer looks and acts exactly like a batch file on disk.
 *
 * If the network is reasonably active, and reporting is slow enough, there is no disk I/O, it all happens
 * in-memory.
 *
 * Also of note: the in-memory buffers (both mCurrentReports and mCurrentReportsSendBuffer) are saved
 * when the service is destroyed.
 */
public class DataStorageManager {
    private static final String LOG_TAG = AppGlobals.makeLogTag(DataStorageManager.class.getSimpleName());

    // Used to cap the amount of data stored. When this limit is hit, no more data is saved to disk
    // until the data is uploaded, or and data exceeds DEFAULT_MAX_WEEKS_DATA_ON_DISK.
    private static final long DEFAULT_MAX_BYTES_STORED_ON_DISK = 1024 * 250; // 250 KiB max by default

    // Used as a safeguard to ensure stumbling data is not persisted. The intended use case of the stumbler lib is not
    // for long-term storage, and so if ANY data on disk is this old, ALL data is wiped as a privacy mechanism.
    private static final int DEFAULT_MAX_WEEKS_DATA_ON_DISK = 2;

    // Set to the default value specified above.
    private final long mMaxBytesDiskStorage;

    // Set to the default value specified above.
    private final int mMaxWeeksStored;

    final ReportBatchBuilder mCurrentReports = new ReportBatchBuilder();
    private final File mReportsDir;
    private final File mStatsFile;
    private final StorageIsEmptyTracker mTracker;

    private static DataStorageManager sInstance;

    private ReportBatch mCurrentReportsSendBuffer;
    private ReportBatchIterator mReportBatchIterator;
    private ReportFileList mFileList;
    private Timer mFlushMemoryBuffersToDiskTimer;

    static final String SEP_REPORT_COUNT = "-r";
    static final String SEP_WIFI_COUNT = "-w";
    static final String SEP_CELL_COUNT = "-c";
    static final String SEP_TIME_MS = "-t";
    static final String FILENAME_PREFIX = "reports";
    static final String MEMORY_BUFFER_NAME = "in memory send buffer";

    public static class QueuedCounts {
        public final int mReportCount;
        public final int mWifiCount;
        public final int mCellCount;
        public final long mBytes;

        QueuedCounts(int reportCount, int wifiCount, int cellCount, long bytes) {
            this.mReportCount = reportCount;
            this.mWifiCount = wifiCount;
            this.mCellCount = cellCount;
            this.mBytes = bytes;
        }
    }

    /* Some data is calculated on-demand, don't abuse this function */
    public synchronized QueuedCounts getQueuedCounts() {
        int reportCount = mFileList.mReportCount + mCurrentReports.reportsCount();
        int wifiCount = mFileList.mWifiCount + mCurrentReports.wifiCount;
        int cellCount = mFileList.mCellCount + mCurrentReports.cellCount;
        long byteLength = 0;

        if (mCurrentReports.reportsCount() > 0) {
            byte[] bytes;
            bytes = Zipper.zipData(mCurrentReports.finalizeReports().getBytes());
            if (bytes == null) {
                Log.e(LOG_TAG, "Error compressing current reports queued", new RuntimeException("GZip Failure"));
            } else {
                byteLength += bytes.length;
            }
            if (mFileList.mReportCount > 0) {
                byteLength += mFileList.mFilesOnDiskBytes;
            }
        }

        if (mCurrentReportsSendBuffer != null) {
            reportCount += mCurrentReportsSendBuffer.reportCount;
            wifiCount += mCurrentReportsSendBuffer.wifiCount;
            cellCount += mCurrentReportsSendBuffer.cellCount;
            byteLength += mCurrentReportsSendBuffer.data.length;
        }
        return new QueuedCounts(reportCount, wifiCount, cellCount, byteLength);
    }

    private static class ReportFileList {
        File[] mFiles;
        int mReportCount;
        int mWifiCount;
        int mCellCount;
        long mFilesOnDiskBytes;

        public ReportFileList() {}
        public ReportFileList(ReportFileList other) {
            if (other == null) {
                return;
            }

            if (other.mFiles != null) {
                mFiles = other.mFiles.clone();
            }

            mReportCount = other.mReportCount;
            mWifiCount = other.mWifiCount;
            mCellCount = other.mCellCount;
            mFilesOnDiskBytes = other.mFilesOnDiskBytes;
        }

        void update(File directory) {
            mFiles = directory.listFiles();
            if (mFiles == null) {
                return;
            }

            if (AppGlobals.isDebug) {
                for (File f : mFiles) {
                    Log.d("StumblerFiles", f.getName());
                }
            }

            mFilesOnDiskBytes = mReportCount = mWifiCount = mCellCount = 0;
            for (File f : mFiles) {
                mReportCount += (int) getLongFromFilename(f.getName(), SEP_REPORT_COUNT);
                mWifiCount += (int) getLongFromFilename(f.getName(), SEP_WIFI_COUNT);
                mCellCount += (int) getLongFromFilename(f.getName(), SEP_CELL_COUNT);
                mFilesOnDiskBytes += f.length();
            }
        }
    }

    public static class ReportBatch {
        public final String filename;
        public final byte[] data;
        public final int reportCount;
        public final int wifiCount;
        public final int cellCount;

        public ReportBatch(String filename, byte[] data, int reportCount, int wifiCount, int cellCount) {
            this.filename = filename;
            this.data = data;
            this.reportCount = reportCount;
            this.wifiCount = wifiCount;
            this.cellCount = cellCount;
        }
    }

    private static class ReportBatchIterator {
        public ReportBatchIterator(ReportFileList list) {
            fileList = new ReportFileList(list);
        }

        static final int BATCH_INDEX_FOR_MEM_BUFFER = -1;
        public int currentIndex = BATCH_INDEX_FOR_MEM_BUFFER;
        public final ReportFileList fileList;
    }

    public interface StorageIsEmptyTracker {
        public void notifyStorageStateEmpty(boolean isEmpty);
    }

    private String getStorageDir(Context c) {
        File dir = null;
        if (AppGlobals.isDebug) {
            // in debug, put files in public location
            dir = c.getExternalFilesDir(null);
            if (dir != null) {
                dir = new File(dir.getAbsolutePath() + "/mozstumbler");
            }
        }

        if (dir == null) {
            dir = c.getFilesDir();
        }

        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            if (!ok) {
                Log.d(LOG_TAG, "getStorageDir: error in mkdirs()");
            }
        }

        return dir.getPath();
    }

    public static synchronized void createGlobalInstance(Context context, StorageIsEmptyTracker tracker) {
        DataStorageManager.createGlobalInstance(context, tracker,
                DEFAULT_MAX_BYTES_STORED_ON_DISK, DEFAULT_MAX_WEEKS_DATA_ON_DISK);
    }

    public static synchronized DataStorageManager createGlobalInstance(Context context, StorageIsEmptyTracker tracker,
                                                         long maxBytesStoredOnDisk, int maxWeeksDataStored) {
        if (sInstance == null) {
            sInstance = new DataStorageManager(context, tracker, maxBytesStoredOnDisk, maxWeeksDataStored);
        }
        return sInstance;
    }

    public static synchronized DataStorageManager getInstance() {
        return sInstance;
    }

    private DataStorageManager(Context c, StorageIsEmptyTracker tracker,
                               long maxBytesStoredOnDisk, int maxWeeksDataStored) {
        mMaxBytesDiskStorage = maxBytesStoredOnDisk;
        mMaxWeeksStored = maxWeeksDataStored;
        mTracker = tracker;
        final String baseDir = getStorageDir(c);
        mStatsFile = new File(baseDir, "upload_stats.ini");
        mReportsDir = new File(baseDir + "/reports");
        if (!mReportsDir.exists()) {
            mReportsDir.mkdirs();
        }
        mFileList = new ReportFileList();
        mFileList.update(mReportsDir);
    }

    public synchronized int getMaxWeeksStored() {
        return mMaxWeeksStored;
    }

    private static byte[] readFile(File file) throws IOException {
        final RandomAccessFile f = new RandomAccessFile(file, "r");
        try {
            final byte[] data = new byte[(int) f.length()];
            f.readFully(data);
            return data;
        } finally {
            f.close();
        }
    }

    public synchronized boolean isDirEmpty() {
        return (mFileList.mFiles == null || mFileList.mFiles.length < 1);
    }

    /* Pass filename returned from dataToSend() */
    public synchronized boolean delete(String filename) {
        // do not use .equals()
        //noinspection StringEquality
        if (filename == MEMORY_BUFFER_NAME) {
            mCurrentReportsSendBuffer = null;
            return true;
        }

        final File file = new File(mReportsDir, filename);
        final boolean ok = file.delete();
        mFileList.update(mReportsDir);
        return ok;
    }

    private static long getLongFromFilename(String name, String separator) {
        final int s = name.indexOf(separator) + separator.length();
        int e = name.indexOf('-', s);
        if (e < 0) {
            e = name.indexOf('.', s);
        }
        return Long.parseLong(name.substring(s, e));
    }

    /* return name of file used, or memory buffer sentinel value.
     * The return value is used to delete the file/buffer later. */
    public synchronized ReportBatch getFirstBatch() throws IOException {
        final boolean dirEmpty = isDirEmpty();
        final int currentReportsCount = mCurrentReports.reportsCount();

        if (dirEmpty && currentReportsCount < 1) {
            return null;
        }

        mReportBatchIterator = new ReportBatchIterator(mFileList);

        if (currentReportsCount > 0) {
            final String filename = MEMORY_BUFFER_NAME;
            final byte[] data = Zipper.zipData(mCurrentReports.finalizeReports().getBytes());
            final int wifiCount = mCurrentReports.wifiCount;
            final int cellCount = mCurrentReports.cellCount;
            clearCurrentReports();
            final ReportBatch result = new ReportBatch(filename, data, currentReportsCount, wifiCount, cellCount);
            mCurrentReportsSendBuffer = result;
            return result;
        } else {
            return getNextBatch();
        }
    }

    private void clearCurrentReports() {
        mCurrentReports.clearReports();
         mCurrentReports.wifiCount = mCurrentReports.cellCount = 0;
    }

    public synchronized ReportBatch getNextBatch() throws IOException {
        if (mReportBatchIterator == null) {
            return null;
        }

        mReportBatchIterator.currentIndex++;
        if (mReportBatchIterator.currentIndex < 0 ||
            mReportBatchIterator.currentIndex > mReportBatchIterator.fileList.mFiles.length - 1) {
            return null;
        }

        final File f = mReportBatchIterator.fileList.mFiles[mReportBatchIterator.currentIndex];
        final String filename = f.getName();
        final int reportCount = (int) getLongFromFilename(f.getName(), SEP_REPORT_COUNT);
        final int wifiCount = (int) getLongFromFilename(f.getName(), SEP_WIFI_COUNT);
        final int cellCount = (int) getLongFromFilename(f.getName(), SEP_CELL_COUNT);
        final byte[] data = readFile(f);
        return new ReportBatch(filename, data, reportCount, wifiCount, cellCount);
    }

    private File createFile(int reportCount, int wifiCount, int cellCount) {
        final long time = System.currentTimeMillis();
        final String name = FILENAME_PREFIX +
                      SEP_TIME_MS + time +
                      SEP_REPORT_COUNT + reportCount +
                      SEP_WIFI_COUNT + wifiCount +
                      SEP_CELL_COUNT + cellCount + ".gz";
        return new File(mReportsDir, name);
    }

    public synchronized long getOldestBatchTimeMs() {
        if (isDirEmpty()) {
            return 0;
        }

        long oldest = Long.MAX_VALUE;
        for (File f : mFileList.mFiles) {
            final long t = getLongFromFilename(f.getName(), SEP_TIME_MS);
            if (t < oldest) {
                oldest = t;
            }
        }
        return oldest;
    }

    public synchronized void saveCurrentReportsSendBufferToDisk() throws IOException {
        if (mCurrentReportsSendBuffer == null || mCurrentReportsSendBuffer.reportCount < 1) {
            return;
        }

        saveToDisk(mCurrentReportsSendBuffer.data,
                   mCurrentReportsSendBuffer.reportCount,
                   mCurrentReportsSendBuffer.wifiCount,
                   mCurrentReportsSendBuffer.cellCount);
        mCurrentReportsSendBuffer = null;
    }

    private void saveToDisk(byte[] bytes, int reportCount, int wifiCount, int cellCount)
      throws IOException {
        if (mFileList.mFilesOnDiskBytes > mMaxBytesDiskStorage) {
            return;
        }

        final FileOutputStream fos = new FileOutputStream(createFile(reportCount, wifiCount, cellCount));
        try {
            fos.write(bytes);
        } finally {
            fos.close();
        }
        mFileList.update(mReportsDir);
    }

    public synchronized void saveCurrentReportsToDisk() throws IOException {
        saveCurrentReportsSendBufferToDisk();
        if (mCurrentReports.reportsCount() < 1) {
            return;
        }
        final byte[] bytes = Zipper.zipData(mCurrentReports.finalizeReports().getBytes());
        saveToDisk(bytes, mCurrentReports.reportsCount(), mCurrentReports.wifiCount, mCurrentReports.cellCount);
        clearCurrentReports();
    }

    public synchronized void insert(String report, int wifiCount, int cellCount) throws IOException {
        notifyStorageIsEmpty(false);

        if (mFlushMemoryBuffersToDiskTimer != null) {
            mFlushMemoryBuffersToDiskTimer.cancel();
            mFlushMemoryBuffersToDiskTimer = null;
        }

        mCurrentReports.addReport(report);
        mCurrentReports.wifiCount += wifiCount;
        mCurrentReports.cellCount += cellCount;

        if (mCurrentReports.maxReportsReached()) {
            // save to disk
            saveCurrentReportsToDisk();
        } else {
            // Schedule a timer to flush to disk after a few mins.
            // If collection stops and wifi not available for uploading, the memory buffer is flushed to disk.
            final int kMillis = 1000 * 60 * 3;
            mFlushMemoryBuffersToDiskTimer = new Timer();
            mFlushMemoryBuffersToDiskTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        saveCurrentReportsToDisk();
                    } catch (IOException ex) {
                        Log.e(LOG_TAG, "mFlushMemoryBuffersToDiskTimer exception", ex);
                    }
                }
            }, kMillis);
        }
    }

    public synchronized Properties readSyncStats() throws IOException {
        if (!mStatsFile.exists()) {
            return new Properties();
        }

        final FileInputStream input = new FileInputStream(mStatsFile);
        try {
            final Properties props = new Properties();
            props.load(input);
            return props;
        } finally {
            input.close();
        }
    }

    public synchronized void incrementSyncStats(long bytesSent, long reports, long cells, long wifis) throws IOException {
        if (reports + cells + wifis < 1) {
            return;
        }

        final Properties properties = readSyncStats();
        final long time = System.currentTimeMillis();
        writeSyncStats(time,
            Long.parseLong(properties.getProperty(DataStorageContract.Stats.KEY_BYTES_SENT, "0")) + bytesSent,
            Long.parseLong(properties.getProperty(DataStorageContract.Stats.KEY_OBSERVATIONS_SENT, "0")) + reports,
            Long.parseLong(properties.getProperty(DataStorageContract.Stats.KEY_CELLS_SENT, "0")) + cells,
            Long.parseLong(properties.getProperty(DataStorageContract.Stats.KEY_WIFIS_SENT, "0")) + wifis);
    }

    public void writeSyncStats(long time, long bytesSent, long totalObs, long totalCells, long totalWifis) throws IOException {
        final FileOutputStream out = new FileOutputStream(mStatsFile);
        try {
            final Properties props = new Properties();
            props.setProperty(DataStorageContract.Stats.KEY_LAST_UPLOAD_TIME, String.valueOf(time));
            props.setProperty(DataStorageContract.Stats.KEY_BYTES_SENT, String.valueOf(bytesSent));
            props.setProperty(DataStorageContract.Stats.KEY_OBSERVATIONS_SENT, String.valueOf(totalObs));
            props.setProperty(DataStorageContract.Stats.KEY_CELLS_SENT, String.valueOf(totalCells));
            props.setProperty(DataStorageContract.Stats.KEY_WIFIS_SENT, String.valueOf(totalWifis));
            props.setProperty(DataStorageContract.Stats.KEY_VERSION, String.valueOf(DataStorageContract.Stats.VERSION_CODE));
            props.store(out, null);
        } finally {
            out.close();
        }
    }

    public synchronized void deleteAll() {
        if (mFileList.mFiles == null) {
            return;
        }

        for (File f : mFileList.mFiles) {
            f.delete();
        }
        mFileList.update(mReportsDir);
    }

    private void notifyStorageIsEmpty(boolean isEmpty) {
        if (mTracker != null) {
            mTracker.notifyStorageStateEmpty(isEmpty);
        }
    }
}
