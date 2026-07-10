import { 
  Bot, 
  Code, 
  Globe,
  Sparkles,
  FileText,
  Lightbulb
} from 'lucide-react';

// Icon 映射 — ⚠️ 使用 any 避免 TS 与 lucide-react 类型兼容性问题
export const ICON_MAP: Record<string, React.ComponentType<any>> = {
  Bot: Bot as React.ComponentType<any>,
  Sparkles: Sparkles as React.ComponentType<any>,
  Code: Code as React.ComponentType<any>,
  FileText: FileText as React.ComponentType<any>,
  Globe: Globe as React.ComponentType<any>,
  Lightbulb: Lightbulb as React.ComponentType<any>,
};
