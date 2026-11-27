<div dir="rtl">

# SamadYar (سمادیار) 🍽️

اپلیکیشن اندرویدی سبک و متن‌باز برای بررسی خودکار و رزرو وعده ناهار هفته آینده در سامانه سماد دانشگاه علم و صنعت ایران (`stu.iust.ac.ir`).

## ✨ امکانات
- **پایش خودکار در پس‌زمینه:** بررسی وضعیت برنامه غذایی هر ۵ دقیقه یک‌بار (حتی در زمان قفل بودن صفحه گوشی با استفاده از Foreground Service).
- **انتخاب سلف از منوی کشویی:** دریافت خودکار لیست سلف‌های مجاز دانشجو از سامانه سماد.
- **اطلاع‌رسانی فوری روی صفحه قفل:** ارسال نوتیفیکیشن صدادار و ویبره‌دار به محض اولین رزرو موفق.
- **نمایش موجودی و وضعیت هفته:** نمایش موجودی کیف پول سماد، وضعیت تک‌تک روزهای هفته آینده (شنبه تا چهارشنبه) و لاگ لحظه‌ای پیام‌های سرور.
- **حفظ حریم خصوصی:** اطلاعات کاربری (شماره دانشجویی و رمز عبور) فقط به‌صورت محلی روی گوشی خودتان ذخیره می‌شود و ارتباط مستقیماً با سرور دانشگاه برقرار می‌گردد (بدون هیچ سرور واسط).

## 📥 دانلود و نصب
1. از بخش [**Releases**](https://github.com/Abolfazl-Arzandi/SamadReserveApp/releases/latest) آخرین نسخه فایل `SamadYar-v1.0.0.apk` را دانلود و نصب کنید.
2. در اولین اجرا، دسترسی **Notification** را تایید کرده و شماره دانشجویی و رمز عبور سامانه سماد را وارد کنید.
3. سلف مورد نظر خود را از منوی کشویی انتخاب کنید و دکمه **Auto Monitor** را روشن بگذارید (یا برای بررسی آنی از **CHECK NOW** استفاده کنید).

> **نکته مهم (گوشی‌های شیائومی، سامسونگ و هوآوی):**  
> برای جلوگیری از بسته شدن برنامه هنگام قفل بودن طولانی صفحه، از مسیر زیر محدودیت باتری را بردارید:  
> `Settings -> Apps -> SamadYar -> Battery -> Unrestricted (بدون محدودیت)`

## ⚠️ سلب مسئولیت
این برنامه یک ابزار غیررسمی و دانشجویی است و هیچ وابستگی رسمی به سامانه سماد یا اداره تغذیه دانشگاه علم و صنعت ندارد. سورس‌کد به‌صورت کامل برای بررسی و اطمینان دانشجویان در دسترس است.

</div>

---

# SamadYar 🍽️

A lightweight, open-source Android application for automatically checking and reserving next week's lunch meals on the Iran University of Science and Technology (IUST) Samad portal (`stu.iust.ac.ir`).

## ✨ Features
- **Background Auto-Monitoring:** Checks for available lunch slots every 5 minutes using an Android Foreground Service, even when the screen is locked.
- **Dynamic Dining Hall (Self) Dropdown:** Automatically fetches and displays the authorized dining halls for your student account.
- **Lock-Screen Confirmation Alerts:** Sends a high-priority notification with sound and vibration as soon as the first successful reservation is completed.
- **Live Wallet & Schedule View:** Displays your current Samad credit balance, day-by-day reservation status (Saturday–Wednesday), and live server logs.
- **Privacy First:** Credentials are stored locally on your device (`SharedPreferences`) and sent directly to `stu.iust.ac.ir` with no third-party servers involved.

## 📥 Download & Setup
1. Download the latest APK from [**Releases**](https://github.com/Abolfazl-Arzandi/SamadReserveApp/releases/latest) and install it on your Android device (Android 8.0+).
2. On first launch, grant notification permission and enter your IUST Student ID and Samad password.
3. Select your preferred dining hall from the dropdown and toggle **Auto Monitor** on (or tap **CHECK NOW** for an immediate check).

> **Battery Optimization Tip:**  
> To prevent Android from killing the background monitor while the screen is locked, set battery usage to **Unrestricted**:  
> `Settings -> Apps -> SamadYar -> Battery -> Unrestricted`

## 🛠️ Tech Stack
- **Language:** Kotlin
- **Networking & HTML Parsing:** OkHttp + Jsoup
- **Concurrency & Background:** Kotlin Coroutines + Android Foreground Service (`dataSync`)
- **CI/CD:** GitHub Actions (automated APK build)

## ⚠️ Disclaimer
This is an unofficial student-made helper tool and is not affiliated with IUST or the Samad system. All source code is publicly available for transparency.
