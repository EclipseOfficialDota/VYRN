package xyz.eclipseofficial.vyrn.compat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.regex.Pattern;

/** Visible brand text and monochrome rendering of the owner's original logo. */
public final class VyrnBrand {
    private static final Pattern NAME=Pattern.compile("(?i)(?<![./:@\\w])telegram(?![.\\w])|تلگرام");
    private static Bitmap notification;
    public static String text(String value){return value==null?null:NAME.matcher(value).replaceAll("Vyrn");}
    public static synchronized Bitmap notificationBitmap(Context context){
        return notificationBitmap(context.getResources());
    }
    public static synchronized Bitmap notificationBitmap(android.content.res.Resources resources){
        if(notification!=null)return notification;
        notification=renderLogo(resources,96);return notification;
    }
    public static Bitmap renderLogo(android.content.res.Resources resources,int size){
        Bitmap source=BitmapFactory.decodeResource(resources,org.telegram.messenger.R.drawable.vyrn_logo);
        int w=source.getWidth(),h=source.getHeight();int[] pixels=new int[w*h];source.getPixels(pixels,0,w,0,0,w,h);
        for(int i=0;i<pixels.length;i++){int c=pixels[i];int light=Math.max((c>>16)&255,Math.max((c>>8)&255,c&255));int alpha=light<64?0:Math.min(255,(light-64)*255/160);pixels[i]=(alpha<<24)|0xffffff;}
        Bitmap mask=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
        Bitmap output=Bitmap.createScaledBitmap(mask,size,size,true);if(output!=mask)mask.recycle();source.recycle();return output;
    }
    private VyrnBrand(){}
}
