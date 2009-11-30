package com.cooliris.media;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.util.Log;

import com.cooliris.cache.CacheService;

public final class LocalDataSource implements DataSource {
    private static final String TAG = "LocalDataSource";

    public static final DiskCache sThumbnailCache = new DiskCache("local-image-thumbs");
    public static final DiskCache sThumbnailCacheVideo = new DiskCache("local-video-thumbs");

    public static final String CAMERA_STRING = "Camera";
    public static final String DOWNLOAD_STRING = "download";
    public static final String CAMERA_BUCKET_NAME = Environment.getExternalStorageDirectory().toString() + "/DCIM/" + CAMERA_STRING;
    public static final String DOWNLOAD_BUCKET_NAME = Environment.getExternalStorageDirectory().toString() + "/" + DOWNLOAD_STRING;
    public static final int CAMERA_BUCKET_ID = getBucketId(CAMERA_BUCKET_NAME);
    public static final int DOWNLOAD_BUCKET_ID = getBucketId(DOWNLOAD_BUCKET_NAME);
    private boolean mDisableImages;
    private boolean mDisableVideos;

    /**
     * Matches code in MediaProvider.computeBucketValues. Should be a common function.
     */
    public static int getBucketId(String path) {
        return (path.toLowerCase().hashCode());
    }

    private Context mContext;
    private ContentObserver mImagesObserver;
    private ContentObserver mVideosObserver;

    public LocalDataSource(Context context) {
        mContext = context;
    }

    public void setMimeFilter(boolean disableImages, boolean disableVideos) {
        mDisableImages = disableImages;
        mDisableVideos = disableVideos;
    }

    public void loadMediaSets(final MediaFeed feed) {
        if (mContext == null) {
            return;
        }
        stopListeners();
        CacheService.loadMediaSets(feed, this, !mDisableImages, !mDisableVideos);
        Handler handler = ((Gallery) mContext).getHandler();
        ContentObserver imagesObserver = new ContentObserver(handler) {
            public void onChange(boolean selfChange) {
                if (((Gallery) mContext).isPaused()) {
                    refresh(feed, CAMERA_BUCKET_ID);
                    refresh(feed, DOWNLOAD_BUCKET_ID);

                    MediaSet set = feed.getCurrentSet();
                    if (set != null && set.mPicasaAlbumId == Shared.INVALID) {
                        refresh(feed, set.mId);
                    }
                }
            }
        };
        ContentObserver videosObserver = new ContentObserver(handler) {
            public void onChange(boolean selfChange) {
                if (((Gallery) mContext).isPaused()) {
                    refresh(feed, CAMERA_BUCKET_ID);
                }
            }
        };

        // Start listening. TODO: coalesce update notifications while mediascanner is active.
        Uri uriImages = Images.Media.EXTERNAL_CONTENT_URI;
        Uri uriVideos = Video.Media.EXTERNAL_CONTENT_URI;
        ContentResolver cr = mContext.getContentResolver();
        mImagesObserver = imagesObserver;
        mVideosObserver = videosObserver;
        cr.registerContentObserver(uriImages, false, mImagesObserver);
        cr.registerContentObserver(uriVideos, false, mVideosObserver);
    }

    public void shutdown() {
        if (ImageManager.isMediaScannerScanning(mContext.getContentResolver())) {
            stopListeners();
        }
    }
    
    private void stopListeners() {
        ContentResolver cr = mContext.getContentResolver();
        if (mImagesObserver != null) {
            cr.unregisterContentObserver(mImagesObserver);
        }
        if (mVideosObserver != null) {
            cr.unregisterContentObserver(mVideosObserver);
        }
    }

    protected void refresh(MediaFeed feed, long setIdToUse) {
        if (setIdToUse == Shared.INVALID) {
            return;
        }
        Log.i(TAG, "Refreshing local data source");
        Gallery.NEEDS_REFRESH = true;
        if (feed.getMediaSet(setIdToUse) == null) {
            if (!CacheService.setHasItems(mContext.getContentResolver(), setIdToUse))
                return;
            MediaSet mediaSet = feed.addMediaSet(setIdToUse, this);
            if (setIdToUse == CAMERA_BUCKET_ID) {
                mediaSet.mName = CAMERA_STRING;
            } else if (setIdToUse == DOWNLOAD_BUCKET_ID) {
                mediaSet.mName = DOWNLOAD_STRING;
            }
            mediaSet.generateTitle(true);
            if (!CacheService.isPresentInCache(setIdToUse))
                CacheService.markDirty(mContext);
        } else {
            MediaSet mediaSet = feed.replaceMediaSet(setIdToUse, this);
            if (setIdToUse == CAMERA_BUCKET_ID) {
                mediaSet.mName = CAMERA_STRING;
            } else if (setIdToUse == DOWNLOAD_BUCKET_ID) {
                mediaSet.mName = DOWNLOAD_STRING;
            }
            mediaSet.generateTitle(true);
            CacheService.markDirty(mContext, setIdToUse);
        }
    }

    public void loadItemsForSet(final MediaFeed feed, final MediaSet parentSet, int rangeStart, int rangeEnd) {
        // Quick load from the cache.
        if (mContext == null || parentSet == null) {
            return;
        }
        loadMediaItemsIntoMediaFeed(feed, parentSet, rangeStart, rangeEnd);
    }

    private void loadMediaItemsIntoMediaFeed(final MediaFeed mediaFeed, final MediaSet set, int rangeStart, int rangeEnd) {
        if (rangeEnd - rangeStart < 0) {
            return;
        }
        CacheService.loadMediaItemsIntoMediaFeed(mediaFeed, set, rangeStart, rangeEnd, !mDisableImages, !mDisableVideos);
        if (set.mId == CAMERA_BUCKET_ID && set.mNumItemsLoaded > 0) {
            mediaFeed.moveSetToFront(set);
        }
    }

    public boolean performOperation(final int operation, final ArrayList<MediaBucket> mediaBuckets, final Object data) {
        int numBuckets = mediaBuckets.size();
        ContentResolver cr = mContext.getContentResolver();
        switch (operation) {
        case MediaFeed.OPERATION_DELETE:
            for (int i = 0; i < numBuckets; ++i) {
                MediaBucket bucket = mediaBuckets.get(i);
                MediaSet set = bucket.mediaSet;
                ArrayList<MediaItem> items = bucket.mediaItems;
                if (set != null && items == null) {
                    // Remove the entire bucket.
                    final Uri uriImages = Images.Media.EXTERNAL_CONTENT_URI;
                    final Uri uriVideos = Video.Media.EXTERNAL_CONTENT_URI;
                    final String whereImages = Images.ImageColumns.BUCKET_ID + "=" + Long.toString(set.mId);
                    final String whereVideos = Video.VideoColumns.BUCKET_ID + "=" + Long.toString(set.mId);
                    cr.delete(uriImages, whereImages, null);
                    cr.delete(uriVideos, whereVideos, null);
                    CacheService.markDirty(mContext);
                }
                if (set != null && items != null) {
                    // We need to remove these items from the set.
                    int numItems = items.size();
                    for (int j = 0; j < numItems; ++j) {
                        MediaItem item = items.get(j);
                        cr.delete(Uri.parse(item.mContentUri), null, null);
                    }
                    set.updateNumExpectedItems();
                    set.generateTitle(true);
                    CacheService.markDirty(mContext, set.mId);
                }
            }
            break;
        case MediaFeed.OPERATION_ROTATE:
            for (int i = 0; i < numBuckets; ++i) {
                MediaBucket bucket = mediaBuckets.get(i);
                ArrayList<MediaItem> items = bucket.mediaItems;
                if (items == null) {
                    continue;
                }
                float angleToRotate = ((Float) data).floatValue();
                if (angleToRotate == 0) {
                    return true;
                }
                int numItems = items.size();
                for (int j = 0; j < numItems; ++j) {
                    rotateItem(items.get(j), angleToRotate);
                }
            }
            break;
        }
        return true;
    }

    private void rotateItem(final MediaItem item, float angleToRotate) {
        ContentResolver cr = mContext.getContentResolver();
        try {
            int currentOrientation = (int) item.mRotation;
            angleToRotate += currentOrientation;
            float rotation = Shared.normalizePositive(angleToRotate);
            String rotationString = Integer.toString((int) rotation);

            // Update the database entry.
            ContentValues values = new ContentValues();
            values.put(Images.ImageColumns.ORIENTATION, rotationString);
            cr.update(Uri.parse(item.mContentUri), values, null, null);

            // Update the file EXIF information.
            Uri uri = Uri.parse(item.mContentUri);
            String uriScheme = uri.getScheme();
            if (uriScheme.equals("file")) {
                ExifInterface exif = new ExifInterface(uri.getPath());
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(Shared.degreesToExifOrientation(rotation)));
                exif.saveAttributes();
            }

            // Invalidate the cache entry.
            CacheService.markDirty(mContext, item.mParentMediaSet.mId);

            // Update the object representation of the item.
            item.mRotation = rotation;
        } catch (Exception e) {
            // System.out.println("Apparently not a JPEG");
        }
    }

    public DiskCache getThumbnailCache() {
        return sThumbnailCache;
    }

    public static MediaItem createMediaItemFromUri(Context context, Uri target) {
        MediaItem item = null;
        long id = ContentUris.parseId(target);
        ContentResolver cr = context.getContentResolver();
        String whereClause = Images.ImageColumns._ID + "=" + Long.toString(id);
        Cursor cursor = cr.query(Images.Media.EXTERNAL_CONTENT_URI, CacheService.PROJECTION_IMAGES, whereClause, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                item = new MediaItem();
                CacheService.populateMediaItemFromCursor(item, cr, cursor, Images.Media.EXTERNAL_CONTENT_URI.toString() + "/");
            }
            cursor.close();
            cursor = null;
        }
        return item;
    }

    public static MediaItem createMediaItemFromFileUri(Context context, String fileUri) {
        MediaItem item = null;
        String filepath = new File(URI.create(fileUri)).toString();
        ContentResolver cr = context.getContentResolver();
        long bucketId = SingleDataSource.parseBucketIdFromFileUri(fileUri);
        String whereClause = Images.ImageColumns.BUCKET_ID + "=" + bucketId + " AND " + Images.ImageColumns.DATA + "='" + filepath
                + "'";
        Cursor cursor = cr.query(Images.Media.EXTERNAL_CONTENT_URI, CacheService.PROJECTION_IMAGES, whereClause, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                item = new MediaItem();
                CacheService.populateMediaItemFromCursor(item, cr, cursor, Images.Media.EXTERNAL_CONTENT_URI.toString() + "/");
            }
            cursor.close();
            cursor = null;
        }
        return item;
    }
}
