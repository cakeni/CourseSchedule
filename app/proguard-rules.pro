# Project-specific ProGuard rules.

# JSON/备份字段通过Gson反射访问，正式版必须与现有数据格式一致。
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep class com.courseschedule.data.entity.Course { *; }
-keep class com.courseschedule.data.backup.SemesterSnapshot { *; }
-keep class com.courseschedule.data.backup.SettingsSnapshot { *; }
-keep class com.courseschedule.data.backup.ScheduleBackup { *; }
-keep class com.courseschedule.ui.importdata.AcademicSchoolDirectory$DirectoryFile { *; }
-keep class com.courseschedule.ui.importdata.AcademicSchoolDirectory$RawEntry { *; }
-keep class com.courseschedule.ui.importdata.AiWebScheduleRecognizer$AiSchedulePayload { *; }
-keep class com.courseschedule.ui.importdata.AiWebScheduleRecognizer$AiCourse { *; }

# Jsoup使用的静态分析注解不参与Android运行时。
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault
-dontwarn javax.annotation.WillClose
