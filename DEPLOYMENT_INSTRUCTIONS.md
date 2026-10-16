# 🚀 دليل النشر الكامل - Live Device Stream

## المتطلبات الأولية

### 1. تثبيت Git
- حمّل Git من: https://git-scm.com/download/win
- ثبته بالإعدادات الافتراضية
- أعد تشغيل PowerShell بعد التثبيت

### 2. إنشاء حساب GitHub
- روح على: https://github.com/signup
- سجل حساب جديد (مجاناً)

### 3. إنشاء حساب Render
- روح على: https://render.com
- اضغط **Get Started** واختار **Sign up with GitHub**

---

## 📤 الخطوة 1: رفع المشروع على GitHub

### 1.1 افتح PowerShell في مجلد المشروع:
```powershell
cd C:\Users\Anas_lotfy\Downloads\live-device-stream
```

### 1.2 تهيئة Git:
```powershell
git init
git add .
git commit -m "Initial commit: Live Device Stream"
```

### 1.3 إنشاء Repository على GitHub:
1. روح على https://github.com/new
2. اسم الـ Repository: `live-device-stream`
3. اختار **Public**
4. **لا تضيف** README أو .gitignore أو License
5. اضغط **Create repository**

### 1.4 رفع الكود:
```powershell
# استبدل YOUR_USERNAME باسم المستخدم بتاعك على GitHub
git remote add origin https://github.com/YOUR_USERNAME/live-device-stream.git
git branch -M main
git push -u origin main
```

**ملحوظة:** لو طلب منك Username وPassword:
- Username: اسم المستخدم بتاعك على GitHub
- Password: **لازم تستخدم Personal Access Token** (مش الباسورد العادي)
  - روح على: https://github.com/settings/tokens
  - اضغط **Generate new token (classic)**
  - اختار **repo** scope
  - انسخ الـ Token واستخدمه كـ password

---

## 🌐 الخطوة 2: نشر Signaling Server على Render

### 2.1 إنشاء Web Service:
1. روح على https://dashboard.render.com
2. اضغط **New +** → **Web Service**
3. اضغط **Connect account** لربط GitHub (لو أول مرة)
4. اختار الـ repository: `live-device-stream`
5. اضغط **Connect**

### 2.2 إعدادات الـ Service:
- **Name**: `live-device-stream` (أو أي اسم تحبه)
- **Region**: اختار الأقرب ليك
- **Branch**: `main`
- **Root Directory**: `signaling-server`
- **Runtime**: `Node`
- **Build Command**: `npm install`
- **Start Command**: `npm start`
- **Instance Type**: `Free`

### 2.3 Environment Variables:
اضغط **Add Environment Variable** وأضف:
```
PORT = 10000
HOST = 0.0.0.0
NODE_ENV = production
```

### 2.4 إنشاء الـ Service:
1. اضغط **Create Web Service**
2. انتظر 3-5 دقائق للنشر
3. بعد النشر، هتشوف رابط زي: `https://live-device-stream-xxxx.onrender.com`
4. **انسخ الرابط ده!**

---

## 📱 الخطوة 3: استخدام التطبيق

### 3.1 ملف الـ APK:
الملف موجود في:
```
C:\Users\Anas_lotfy\Downloads\live-device-stream\android-app\app\build\outputs\apk\debug\app-debug.apk
```

انقله على تليفونك وثبته.

### 3.2 فتح تطبيق الويب:
افتح المتصفح على الكمبيوتر وروح على:
```
https://live-device-stream-xxxx.onrender.com
```
(استخدم الرابط اللي حصلت عليه من Render)

### 3.3 تشغيل البث:

#### على التليفون:
1. افتح التطبيق
2. في خانة **Signaling Server URL** اكتب:
   ```
   wss://live-device-stream-xxxx.onrender.com
   ```
   **مهم جداً:** استخدم `wss://` (مش `ws://`)
3. هيظهر لك **Room ID** و **Pairing Token**
4. اضغط **Start Session**
5. فعّل الأذونات (Screen/Camera/Mic) حسب رغبتك
6. **انسخ الـ Room ID والـ Token**

#### على الكمبيوتر (في المتصفح):
1. في صفحة الويب، املأ:
   - **Signaling Server URL**: `wss://live-device-stream-xxxx.onrender.com`
   - **Room ID**: اللي نسخته من التليفون
   - **Pairing Code**: اللي نسخته من التليفون
2. اضغط **Connect to Device**
3. هتشوف البث مباشرة! 🎉

---

## 🔧 حل المشاكل الشائعة

### مشكلة: "ROOM_NOT_FOUND"
- تأكد إنك بدأت البث على التليفون **قبل** ما تتصل من الكمبيوتر
- تأكد إن الـ Room ID صحيح

### مشكلة: "Connection Failed"
- تأكد إن السيرفر شغال على Render (الحالة "Active")
- تأكد إنك بتستخدم `wss://` (مش `ws://`)

### مشكلة: "Authentication Failed"
- تأكد إن الـ Pairing Token صحيح
- جرب تعمل Regenerate للـ Session في التطبيق

### ملاحظة مهمة عن Render المجاني:
- السيرفر بيروح في **sleep mode** بعد 15 دقيقة من عدم الاستخدام
- أول اتصال بعد Sleep هياخد 30-60 ثانية عشان السيرفر يصحى
- الحل: استخدم خدمة مثل **UptimeRobot** (مجانية) عشان تعمل ping للسيرفر كل 10 دقائق

---

## ✅ الخلاصة

بعد النشر، هتقدر تراقب تليفونك من **أي مكان في العالم** طالما:
- التليفون متصل بالإنترنت (4G/5G/WiFi)
- الكمبيوتر متصل بالإنترنت
- السيرفر شغال على Render

**مش محتاج يكونوا على نفس الشبكة!** 🌍

---

## 📞 المساعدة

لو واجهت أي مشكلة:
1. تأكد من logs السيرفر على Render
2. افتح Developer Console في المتصفح (F12)
3. شوف الأخطاء في logcat على Android Studio

---

**بالتوفيق! 🚀**
