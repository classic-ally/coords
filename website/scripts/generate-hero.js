import sharp from 'sharp';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { access, mkdir, readFile } from 'fs/promises';

const __dirname = dirname(fileURLToPath(import.meta.url));
const screenshotsDir = join(__dirname, 'screenshots');
const framesDir = join(__dirname, 'frames');
const outputDir = join(__dirname, '..', 'public');

async function fileExists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

async function createDeviceFrame(screenshotPath, device) {
  const configPath = join(framesDir, device, 'config.json');
  const config = JSON.parse(await readFile(configPath, 'utf-8'));

  // Find frame file (could be .png or .webp)
  let framePath = join(framesDir, device, 'frame.png');
  if (!await fileExists(framePath)) {
    framePath = join(framesDir, device, 'back.webp');
  }


  // Resize screenshot to match screen dimensions
  let screenshot = await sharp(screenshotPath)
    .resize(config.screenWidth, config.screenHeight, {
      fit: 'cover',
      position: 'top',
    })
    .toBuffer();

  // Apply rounded corners to screenshot if specified (for iPhone display)
  if (config.screenCornerRadius) {
    const mask = Buffer.from(
      `<svg width="${config.screenWidth}" height="${config.screenHeight}">
        <rect width="${config.screenWidth}" height="${config.screenHeight}"
              rx="${config.screenCornerRadius}" fill="white"/>
      </svg>`
    );
    screenshot = await sharp(screenshot)
      .composite([{ input: mask, blend: 'dest-in' }])
      .png()
      .toBuffer();
  }

  // Load the device frame
  const frame = await sharp(framePath).png().toBuffer();

  // Composite screenshot behind frame (frame has transparent screen area)
  let result = await sharp({
    create: {
      width: config.frameWidth,
      height: config.frameHeight,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([
      // Screenshot first (behind)
      {
        input: screenshot,
        top: config.screenY,
        left: config.screenX,
      },
      // Frame on top
      {
        input: frame,
        top: 0,
        left: 0,
      },
    ])
    .png()
    .toBuffer();

  // Add shadow around device
  const shadowPadding = 50;
  const resultMeta = await sharp(result).metadata();

  // Create a black silhouette of the device
  const silhouette = await sharp({
    create: {
      width: resultMeta.width,
      height: resultMeta.height,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 255 },
    },
  })
    .composite([
      {
        input: await sharp(result).ensureAlpha().toBuffer(),
        blend: 'dest-in',
      },
    ])
    .png()
    .toBuffer();

  // Blur the silhouette for shadow effect
  const blurredShadow = await sharp(silhouette)
    .blur(20)
    .modulate({ lightness: 0.3 })
    .png()
    .toBuffer();

  const withShadow = await sharp({
    create: {
      width: resultMeta.width + shadowPadding * 2,
      height: resultMeta.height + shadowPadding * 2,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([
      // Shadow (offset down and slightly right)
      {
        input: blurredShadow,
        top: shadowPadding + 10,
        left: shadowPadding + 5,
      },
      // Device on top
      {
        input: result,
        top: shadowPadding,
        left: shadowPadding,
      },
    ])
    .png()
    .toBuffer();

  return withShadow;
}

async function generateHero() {
  console.log('Generating hero image...');

  const iphonePath = join(screenshotsDir, 'iphone.png');
  const androidPath = join(screenshotsDir, 'android.png');

  const hasIphone = await fileExists(iphonePath);
  const hasAndroid = await fileExists(androidPath);

  if (!hasIphone && !hasAndroid) {
    console.error('No screenshots found!');
    console.error('Add screenshots to: website/scripts/screenshots/');
    console.error('  - iphone.png');
    console.error('  - android.png');
    process.exit(1);
  }

  console.log(`Found: ${[hasIphone && 'iPhone', hasAndroid && 'Android'].filter(Boolean).join(', ')}`);

  let iphoneFrame, androidFrame;

  if (hasAndroid) {
    console.log('Creating Android frame (Pixel 7)...');
    androidFrame = await createDeviceFrame(androidPath, 'pixel_7');
  }

  if (hasIphone) {
    console.log('Creating iPhone frame (iPhone 17)...');
    iphoneFrame = await createDeviceFrame(iphonePath, 'iphone_17');
  }

  // Get frame dimensions
  const iphoneMeta = iphoneFrame ? await sharp(iphoneFrame).metadata() : null;
  const androidMeta = androidFrame ? await sharp(androidFrame).metadata() : null;

  const composites = [];
  let canvasWidth, canvasHeight;

  if (hasIphone && hasAndroid) {
    // Both devices - iPhone left/front, Android right/behind
    const overlap = 200;
    const androidOffsetY = 271; // Align with iPhone
    const iphoneOffsetY = 80;
    const androidOffsetX = iphoneMeta.width - overlap; // Android starts behind iPhone's right edge

    canvasWidth = iphoneMeta.width + androidMeta.width - overlap;
    canvasHeight = Math.max(androidMeta.height + androidOffsetY, iphoneMeta.height + iphoneOffsetY);

    // Android first (behind), then iPhone (front)
    composites.push({ input: androidFrame, top: androidOffsetY, left: androidOffsetX });
    composites.push({ input: iphoneFrame, top: iphoneOffsetY, left: 0 });
  } else if (hasIphone) {
    canvasWidth = iphoneMeta.width;
    canvasHeight = iphoneMeta.height;
    composites.push({ input: iphoneFrame, top: 0, left: 0 });
  } else {
    canvasWidth = androidMeta.width;
    canvasHeight = androidMeta.height;
    composites.push({ input: androidFrame, top: 0, left: 0 });
  }

  // Create final image
  await mkdir(outputDir, { recursive: true });

  // Full resolution PNG for reference
  const fullResRaw = await sharp({
    create: {
      width: canvasWidth,
      height: canvasHeight,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite(composites)
    .png()
    .toBuffer();

  // Crop to remove wasted space
  const cropLeft = 55;
  const cropRight = 45;
  const cropTop = 140;
  const cropBottom = 40;
  const fullRes = await sharp(fullResRaw)
    .extract({
      left: cropLeft,
      top: cropTop,
      width: canvasWidth - cropLeft - cropRight,
      height: canvasHeight - cropTop - cropBottom,
    })
    .png()
    .toBuffer();

  // Update dimensions after crop
  canvasWidth = canvasWidth - cropLeft - cropRight;
  canvasHeight = canvasHeight - cropTop - cropBottom;

  // Output full-res PNG for reference/debugging
  const pngPath = join(outputDir, 'hero-devices.png');
  await sharp(fullRes).png().toFile(pngPath);
  const pngMeta = await sharp(fullRes).metadata();
  console.log(`\nSaved: public/hero-devices.png (${pngMeta.width}x${pngMeta.height}, full res)`);

  // Output optimized WebP (resized to max 2000px wide, quality 92)
  const webpPath = join(outputDir, 'hero-devices.webp');
  const maxWidth = 2000;
  const scale = maxWidth / canvasWidth;
  const outputWidth = maxWidth;
  const outputHeight = Math.round(canvasHeight * scale);

  await sharp(fullRes)
    .resize(outputWidth, outputHeight)
    .webp({ quality: 92 })
    .toFile(webpPath);

  const stats = await import('fs/promises').then(fs => fs.stat(webpPath));
  const sizeKB = Math.round(stats.size / 1024);

  console.log(`Saved: public/hero-devices.webp (${outputWidth}x${outputHeight}, ${sizeKB}KB)`);
}

generateHero().catch(console.error);
