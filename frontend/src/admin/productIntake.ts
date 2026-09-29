import type { ProductFeatures } from '../api/adminProducts';

export interface FeatureDraft {
  weightGrams: string;
  batteryLifeHours: string;
  batteryLifeScenario: string;
  noiseCancelling: 'unknown' | 'yes' | 'no';
}
export function featureDraft(features: ProductFeatures): FeatureDraft {
  return {
    weightGrams: features.weightGrams == null ? '' : String(features.weightGrams),
    batteryLifeHours: features.batteryLifeHours == null ? '' : String(features.batteryLifeHours),
    batteryLifeScenario: features.batteryLifeScenario ?? '',
    noiseCancelling: features.noiseCancelling == null ? 'unknown' : features.noiseCancelling ? 'yes' : 'no',
  };
}
export function featureValues(draft: FeatureDraft): ProductFeatures {
  const numeric = (value: string) => {
    if (!value.trim()) return null;
    const result = Number(value);
    if (!Number.isFinite(result) || result <= 0) throw new Error('重量和续航必须为正数，未知值请留空');
    return result;
  };
  return {
    weightGrams: numeric(draft.weightGrams), batteryLifeHours: numeric(draft.batteryLifeHours),
    batteryLifeScenario: draft.batteryLifeScenario || null,
    noiseCancelling: draft.noiseCancelling === 'unknown' ? null : draft.noiseCancelling === 'yes',
  };
}
export function hasKnownFeatures(draft: FeatureDraft) {
  return !!(draft.weightGrams || draft.batteryLifeHours || draft.batteryLifeScenario || draft.noiseCancelling !== 'unknown');
}

/** Explicit admin labels only. Free-form prose is never inferred into suitability. */
export function suitabilityTags(value: string): string[] {
  const tags = value.split(/[,，、\n]/).map(tag => tag.trim()).filter(Boolean);
  if (tags.length > 12 || tags.some(tag => !/^[\p{L}\p{N}][\p{L}\p{N}·_-]{0,39}$/u.test(tag))
      || new Set(tags.map(tag => tag.toLocaleLowerCase())).size !== tags.length) {
    throw new Error('每组最多 12 个不重复的简短标签；标签不能包含空格或句子');
  }
  return tags;
}

/** Human-reviewed alternative names, never inferred from descriptions or user queries. */
export function productAliases(value: string): string[] {
  const aliases = value.split(/[,，、\n]/).map(alias => alias.trim()).filter(Boolean);
  if (aliases.length > 10 || aliases.some(alias => alias.length < 2 || alias.length > 200
      || /[\u0000-\u001f\u007f]/.test(alias))
      || new Set(aliases.map(alias => alias.toLocaleUpperCase())).size !== aliases.length) {
    throw new Error('最多录入 10 个不重复的别名，每个别名需为 2–200 字');
  }
  return aliases;
}
