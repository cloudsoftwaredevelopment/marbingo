const path = require('path');
const fs = require('fs');
const multer = require('multer');
const sharp = require('sharp');

const TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const TYPE_MSG = 'Choose a JPG, PNG or WEBP image.';
const root = path.join(__dirname, '..', '..', 'uploads');

// Multer for a single optional image; rejects other types with a 400 instead of dropping them.
const imageUpload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 8 * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    if (TYPES.includes(file.mimetype)) return cb(null, true);
    cb(Object.assign(new Error(TYPE_MSG), { status: 400, expose: true, fields: { photo: TYPE_MSG } }));
  }
});

// Resizes to webp under uploads/<subdir>/ and returns the public URL.
async function saveImage(buffer, subdir, name, resize) {
  fs.mkdirSync(path.join(root, subdir), { recursive: true });
  await sharp(buffer).rotate().resize(resize).webp({ quality: 82 }).toFile(path.join(root, subdir, name));
  return `/uploads/${subdir}/${name}`;
}

module.exports = { imageUpload, saveImage, TYPE_MSG };
