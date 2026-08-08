export interface DeviceLocationContext {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  capturedAt: number;
}

const WEATHER_WORDS = /天气|气温|温度|weather|temperature/i;
const ENGLISH_LOCATION = /(?:weather|temperature)\s+(?:in|for)\s+[a-z][a-z .'-]{1,30}|[a-z][a-z .'-]{1,30}\s+(?:weather|temperature)/i;
const COORDINATES = /-?\d{1,2}(?:\.\d+)?\s*[,，]\s*-?\d{1,3}(?:\.\d+)?/;
const CHINESE_WEATHER = /([\u4e00-\u9fff]{1,20})(?=的?(?:今天|明天|后天)?(?:天气|气温|温度|天气预报))/;

/** True only for a weather lookup that does not already contain a usable place. */
export function needsDeviceLocation(message: string): boolean {
  const normalized = message.trim();
  if (!WEATHER_WORDS.test(normalized)) return false;
  if (ENGLISH_LOCATION.test(normalized) || COORDINATES.test(normalized)) return false;

  const match = CHINESE_WEATHER.exec(normalized);
  if (!match) return true;

  const candidate = match[1]
    .replace(/^(?:请问|麻烦|帮我|帮忙|我想|想知道|看一下|看看|查一下|查询|查查|查|今天|明天|后天)+/, '')
    .replace(/(?:今天|明天|后天|现在|当前|最近|未来)/g, '')
    .replace(/市$/, '')
    .trim();
  return candidate.length < 2;
}

export function getCurrentDeviceLocation(): Promise<DeviceLocationContext> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('geolocation_unavailable'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      position => resolve({
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        accuracyMeters: position.coords.accuracy,
        capturedAt: position.timestamp || Date.now(),
      }),
      reject,
      {
        enableHighAccuracy: false,
        timeout: 8_000,
        maximumAge: 5 * 60_000,
      },
    );
  });
}
