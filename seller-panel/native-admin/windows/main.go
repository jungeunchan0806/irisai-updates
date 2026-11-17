//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"
)

const panelURL = "https://bshack-license-panel.jungeunchan0806.chatgpt.site/?app=admin-windows&version=1.2.0"

func ptr(s string) *uint16 { p, _ := syscall.UTF16PtrFromString(s); return p }
func main() {
	profile := filepath.Join(os.Getenv("LOCALAPPDATA"), "BSHACK", "AdminPanel", "BrowserProfile")
	if err := os.MkdirAll(profile, 0700); err != nil {
		showError("관리자 패널 설정 폴더를 만들 수 없습니다.")
		return
	}
	candidates := []string{
		filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(os.Getenv("LOCALAPPDATA"), "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(os.Getenv("ProgramFiles"), "Google", "Chrome", "Application", "chrome.exe"),
		filepath.Join(os.Getenv("LOCALAPPDATA"), "Google", "Chrome", "Application", "chrome.exe"),
	}
	for _, executable := range candidates {
		if info, err := os.Stat(executable); err == nil && !info.IsDir() {
			if exec.Command(executable, "--app="+panelURL, "--user-data-dir="+profile, "--no-first-run", "--no-default-browser-check", "--new-window").Start() == nil {
				return
			}
		}
	}
	showError("관리자 패널을 열려면 Microsoft Edge 또는 Google Chrome이 필요합니다.")
}
func showError(message string) {
	syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(ptr(message))), uintptr(unsafe.Pointer(ptr("BSHACK 관리자 패널"))), 0x10)
}
