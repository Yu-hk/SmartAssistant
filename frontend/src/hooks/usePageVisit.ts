import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { moduleForPath, sendVisit, visitModules } from '../api/visits';

/** Mounted only inside an admitted customer/admin page or the public login page. */
export function usePageVisit() {
  const { pathname, key } = useLocation();
  const last = useRef('');
  useEffect(() => {
    let active = true;
    const navigation = `${key}:${pathname}`;
    void visitModules().then(modules => {
      if (!active || last.current === navigation) return;
      const module = moduleForPath(modules, pathname);
      if (!module) return;
      last.current = navigation;
      return sendVisit(module);
    }).catch(() => {});
    return () => { active = false; };
  }, [pathname, key]);
}
