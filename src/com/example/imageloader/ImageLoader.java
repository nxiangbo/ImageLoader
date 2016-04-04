package com.example.imageloader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import libcore.io.DiskLruCache;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

public class ImageLoader {
	private static final String TAG = "ImageLoader";

	private Context mContext;
	private LruCache<String, Bitmap> mMemoryCache;
	private DiskLruCache mDiskCache;
	private ImageResizer mImageResizer = new ImageResizer();

	private boolean mIsDiskCacheCreated = false;

	// 磁盘缓存大小
	private static final long DISK_CACHE_SIZE = 10 * 1024 * 1024;

	// 一个节点只设置一个数据，将index设为0
	private static final int DISK_CACHE_INDEX = 0;

	private static final int IO_BUFFER_SIZE = 5 * 1024;

	private static final int TAG_KEY_URI = R.id.imageloader_uri;

	private static final int MESSAGE_POST_RESULT = 1;

	private static final int CPU_COUNT = Runtime.getRuntime()
			.availableProcessors();
	private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
	private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
	private static final long ALIVE = 10L;

	private static final ThreadFactory sThreadFactory = new ThreadFactory() {
		private final AtomicInteger mCount = new AtomicInteger(1);

		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
		}
	};

	// 线程池
	private static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
			CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, ALIVE, TimeUnit.SECONDS,
			new LinkedBlockingQueue<Runnable>(), sThreadFactory);

	private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
		public void handleMessage(android.os.Message msg) {
			LoaderResult result = (LoaderResult) msg.obj;
			ImageView imageView = result.imageView;
			String uri = (String) imageView.getTag(TAG_KEY_URI);
			if (uri.equals(result.uri)) {
				imageView.setImageBitmap(result.bitmap);
			} else {
				Log.w(TAG, "图片url可能已经改变");
			}
		};
	};

	private ImageLoader(Context context) {
		mContext = context.getApplicationContext();
		// 设置内存缓存空间的大小
		int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
		int cacheSize = maxMemory / 8;

		// 实例化LruCache
		mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
			// 计算Bitmap的大小
			@Override
			protected int sizeOf(String key, Bitmap bitmap) {
				return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
			}
		};

		// 创建磁盘缓存的目录
		File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
		if (!diskCacheDir.exists()) {
			diskCacheDir.mkdirs();
		}

		if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
			try {
				mDiskCache = DiskLruCache.open(diskCacheDir, 1, 1,
						DISK_CACHE_SIZE);
				mIsDiskCacheCreated = true;
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public static ImageLoader build(Context context){
		return new ImageLoader(context);
	}

	// 添加到内存缓存
	private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
		if (getBitmapFromMemCache(key) == null) {
			mMemoryCache.put(key, bitmap);
		}
	}

	private Bitmap getBitmapFromMemCache(String key) {
		return mMemoryCache.get(key);
	}

	private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight)
			throws IOException {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			throw new RuntimeException("不能在主线程中访问网络");
		}

		if (mDiskCache == null) {
			return null;
		}

		String key = hashKeyFromUrl(url);
		DiskLruCache.Editor editor = mDiskCache.edit(key);
		if (editor != null) {
			OutputStream out = editor.newOutputStream(DISK_CACHE_INDEX);
			// 如果下载成功，则commit,否则，abort
			if (downloadUrlToStream(url, out)) {
				editor.commit();
			} else {
				editor.abort();
			}
			mDiskCache.flush();
		}

		return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
	}

	// 从磁盘缓存中加载图片
	private Bitmap loadBitmapFromDiskCache(String url, int reqWidth,
			int reqHeight) throws IOException {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			Log.w(TAG, "不推荐在主线程中加载磁盘缓存中的图片");
		}

		if (mDiskCache == null) {
			return null;
		}

		Bitmap bitmap = null;
		String key = hashKeyFromUrl(url);
		DiskLruCache.Snapshot snapShot = mDiskCache.get(key);
		if (snapShot != null) {
			FileInputStream fileInputStream = (FileInputStream) snapShot
					.getInputStream(DISK_CACHE_INDEX);
			FileDescriptor fileDescriptor = fileInputStream.getFD();
			bitmap = mImageResizer.decodeBitmapFromFileDescriptor(
					fileDescriptor, reqWidth, reqHeight);
			if (bitmap != null) {
				addBitmapToMemoryCache(key, bitmap);
			}
		}
		return bitmap;
	}

	private boolean downloadUrlToStream(String urlStr, OutputStream outputStream) {
		HttpURLConnection conn = null;
		BufferedOutputStream out = null;
		BufferedInputStream in = null;
		try {
			URL url = new URL(urlStr);
			conn = (HttpURLConnection) url.openConnection();
			in = new BufferedInputStream(conn.getInputStream(), IO_BUFFER_SIZE);
			out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

			int b;
			while ((b = in.read()) != -1) {
				out.write(b);
			}

			return true;
		} catch (IOException e) {
			e.printStackTrace();
			Log.e(TAG, "下载失败：" + e);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
			try {
				if(in!=null){
					in.close();
				}
				if(out!=null){
					out.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}

		}

		return false;
	}

	// 从内存缓存中加载图片
	private Bitmap loadBitmapFromMemCache(String url) {
		String key = hashKeyFromUrl(url);
		return getBitmapFromMemCache(key);
	}

	// 同步加载方式
	public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
		Bitmap bitmap = loadBitmapFromMemCache(uri);
		if (bitmap != null) {
			Log.d(TAG, "laodBitmapFromMemCache, url:" + uri);
			return bitmap;
		}
		try {
			bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
			if (bitmap != null) {
				Log.d(TAG, "loadBitmapFromDiskCache, url:" + uri);
				return bitmap;
			}

			bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
			Log.d(TAG, "laodBotmapFromHttp");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if (bitmap == null && !mIsDiskCacheCreated) {
			Log.w(TAG, "连接失败，磁盘缓存没有被创建");
			bitmap = downloadBitmapFromUrl(uri);
		}
		return bitmap;
	}

	// 异步加载方式
	public void bindBitmap(final String uri, final ImageView imageView,
			final int reqWidth, final int reqHeight) {
		imageView.setTag(TAG_KEY_URI, uri);
		Bitmap bitmap = loadBitmapFromMemCache(uri);
		if (bitmap != null) {
			imageView.setImageBitmap(bitmap);
			return;
		}

		Runnable loadBitmapTask = new Runnable() {

			@Override
			public void run() {
				Bitmap bitmap = loadBitmap(uri, reqWidth, reqHeight);
				if (bitmap != null) {
					LoaderResult result = new LoaderResult(imageView, uri,
							bitmap);
					mMainHandler.obtainMessage(MESSAGE_POST_RESULT, result)
							.sendToTarget();

				}
			}
		};

		// 执行线程
		THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
	}

	// 从网络中加载bitmap
	private Bitmap downloadBitmapFromUrl(String urlStr) {
		Bitmap bitmap = null;
		HttpURLConnection conn = null;
		BufferedInputStream in = null;
		try {
			URL url = new URL(urlStr);
			conn = (HttpURLConnection) url.openConnection();
			in = new BufferedInputStream(conn.getInputStream(), IO_BUFFER_SIZE);
			bitmap = BitmapFactory.decodeStream(in);
		} catch (IOException e) {
			e.printStackTrace();
			Log.e(TAG, "下载失败。e:" + e);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
			try {
				if(in!=null){
					in.close();
				}
				
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		return bitmap;
	}

	// 使用MD5算法，将URL转换为MD5字符串，即key
	private String hashKeyFromUrl(String url) {
		String cacheKey;
		try {
			MessageDigest mDigest = MessageDigest.getInstance("MD5");
			mDigest.update(url.getBytes());
			cacheKey = bytesToHexString(mDigest.digest());
		} catch (NoSuchAlgorithmException e) {
			cacheKey = String.valueOf(url.hashCode());
			e.printStackTrace();
		}
		return cacheKey;
	}

	private String bytesToHexString(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < bytes.length; i++) {
			String hex = Integer.toHexString(0xFF & bytes[i]);
			if (hex.length() == 1) {
				sb.append('0');
			}
			sb.append(hex);
		}
		return sb.toString();
	}

	// 获取缓存目录下可用的空间
	@SuppressLint("NewApi")
	private long getUsableSpace(File diskCacheDir) {
		// 如果是android 2.3及以上版本的，直接获取可用空间
		if (Build.VERSION.SDK_INT >= VERSION_CODES.GINGERBREAD) {
			return diskCacheDir.getUsableSpace();
		}
		StatFs stats = new StatFs(diskCacheDir.getPath());
		return stats.getBlockSizeLong() * stats.getAvailableBlocksLong();
	}

	private File getDiskCacheDir(Context context, String fileName) {
		boolean externalStorageAvailable = Environment
				.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
		String cachePath;
		if (externalStorageAvailable) {
			cachePath = context.getExternalCacheDir().getPath();
		} else {
			cachePath = context.getCacheDir().getPath();
		}
		return new File(cachePath + File.separator + fileName);
	}

	private static class LoaderResult {
		public ImageView imageView;
		public String uri;
		public Bitmap bitmap;

		public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
			this.imageView = imageView;
			this.uri = uri;
			this.bitmap = bitmap;
		}
	}

}
