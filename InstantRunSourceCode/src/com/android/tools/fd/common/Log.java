/*    */ package com.android.tools.fd.common;
/*    */ 
/*    */ import java.util.logging.Level;
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ public class Log
/*    */ {
/* 27 */   public static Logging logging = null;
/*    */   
/*    */   public static abstract interface Logging
/*    */   {
/*    */     public abstract void log(Level paramLevel, String paramString);
/*    */     
/*    */     public abstract boolean isLoggable(Level paramLevel);
/*    */     
/*    */     public abstract void log(Level paramLevel, String paramString, Throwable paramThrowable);
/*    */   }
/*    */ }


/* Location:              D:\decodeapk\new_aliptrip\instant-run.jar!\com\android\tools\fd\common\Log.class
 * Java compiler version: 6 (50.0)
 * JD-Core Version:       0.7.1
 */