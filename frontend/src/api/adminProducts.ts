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
