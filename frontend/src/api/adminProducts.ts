import { apiClient } from './client';

export interface ProductFeatures {
  weightGrams: number | null;
  batteryLifeHours: number | null;
  batteryLifeScenario: string | null;
  noiseCancelling: boolean | null;
}
export interface ProductExtraction {
  version: string;
  features: ProductFeatures;
  evidence: Partial<Record<keyof ProductFeatures, string>>;
  warnings: string[];
}
export interface ProductIntake {
  productCode: string;
  productName: string;
  category: string;
  price: number;
  stock: string;
  description: string;
  spec: string;
  color: string;
  features: ProductFeatures;
  featuresConfirmed: boolean;
  suitability?: ProductSuitabilityDraft;
  aliases?: string[];
}
export interface ProductSuitabilityDraft {
  audiences: string[];
  useCases: string[];
  source: string;
  confirmed: boolean;
}
export interface ProductCreated {
  productCode: string;
  productName: string;
  revision: number;
  features: ProductFeatures;
  manualOverrides: string[];
}
export const extractProductFeatures = (description: string, spec: string) =>
  apiClient.post<ProductExtraction>('/admin/products/extract-features', { description, spec });
export const createAdminProduct = (product: ProductIntake) =>
  apiClient.post<ProductCreated>('/admin/products', product);
export interface ProductAliasState {
  productCode: string;
  productName: string;
  revision: number;
  aliases: string[];
  updatedBy: number | null;
  updatedAt: string | null;
}
export const getProductAliases = (productCode: string) =>
  apiClient.get<ProductAliasState>(`/admin/products/${encodeURIComponent(productCode)}/aliases`);
export const saveProductAliases = (productCode: string, expectedRevision: number, aliases: string[]) =>
  apiClient.put<ProductAliasState>(`/admin/products/${encodeURIComponent(productCode)}/aliases`,
    { expectedRevision, aliases });
