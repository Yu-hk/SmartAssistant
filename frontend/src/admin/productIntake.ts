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
