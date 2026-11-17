import type {Metadata} from 'next';
import './globals.css';
export const metadata:Metadata={title:'BSHACK · 구독 관리',description:'HWID와 라이선스키로 이용 기간을 관리하는 관리자 패널',robots:{index:false,follow:false}};
export default function Layout({children}:{children:React.ReactNode}){return <html lang="ko"><body>{children}</body></html>;}
