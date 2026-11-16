"use client";

import { useEffect, useState } from "react";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { apiConfigApi } from "@/lib/api/ai-model";

type LoginStatus = "checking" | "unavailable" | "signed-in" | "signed-out" | "error";

const statusLabel: Record<LoginStatus, string> = {
  checking: "正在检查…",
  unavailable: "本机 Codex 不可用",
  "signed-in": "已登录",
  "signed-out": "未登录",
  error: "无法获取登录状态",
};

export function CodexLoginStatus() {
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState<LoginStatus>("checking");

  useEffect(() => {
    let active = true;
    void apiConfigApi.codexStatus().then(({ available, signedIn }) => {
      if (active) {
        setStatus(available ? (signedIn ? "signed-in" : "signed-out") : "unavailable");
      }
    }).catch(() => {
      if (active) {
        setStatus("error");
      }
    });
    return () => {
      active = false;
    };
  }, [attempt]);

  return (
    <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border/20 bg-background/70 p-3">
      <div className="flex min-w-0 flex-col gap-1">
        <p className="text-sm font-medium" role="status">Codex 登录状态 · {statusLabel[status]}</p>
        <p className="text-xs text-muted-foreground">
          {status === "signed-out"
            ? <>请在运行后端服务的账号下执行 <code>codex login</code>，完成后刷新状态。</>
            : "使用运行后端服务的账号已有的 Codex 登录。"}
        </p>
      </div>
      <Button
        variant="outline"
        size="sm"
        disabled={status === "checking"}
        type="button"
        onClick={() => {
          setStatus("checking");
          setAttempt((value) => value + 1);
        }}
      >
        <RefreshCw />
        刷新状态
      </Button>
    </div>
  );
}
