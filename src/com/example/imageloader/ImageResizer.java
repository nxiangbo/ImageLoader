package com.example.imageloader;

import java.io.FileDescriptor;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.util.Log;

public class ImageResizer {
	private final static String TAG = "ImageResizer";
	
	public ImageResizer() {
	}
	
	//压缩资源中的图片
	public  Bitmap decodeBitmapFromResource(Resources res, int resId, int reqWidth, int reqHeight){
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeResource(res, resId, options);
		
		options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
		
		options.inJustDecodeBounds = false;
		return BitmapFactory.decodeResource(res, resId, options);
	}
	
	public Bitmap decodeBitmapFromFileDescriptor(FileDescriptor fd, int reqWidth, int reqHeight){
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true ;
		BitmapFactory.decodeFileDescriptor(fd, null, options);
		
		options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
		
		options.inJustDecodeBounds = false;
		return BitmapFactory.decodeFileDescriptor(fd, null, options);
	}

	private static int calculateInSampleSize(Options options, int reqWidth,
			int reqHeight) {
		final int height = options.outHeight;
		final int width = options.outWidth;
		int inSampleSize = 1;
		if(height> reqHeight||width>reqWidth){
			final int halfHeight = height/2;
			final int halfWidth = width/2;
			while((halfHeight/inSampleSize)>= reqHeight && (halfWidth/inSampleSize)>=reqWidth){
				inSampleSize *= 2;
			}
		}
		Log.d(TAG, "inSampleSize:"+inSampleSize);
		return inSampleSize;
	}
}
