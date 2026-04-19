import { useEffect, useState } from 'react'
import { FileText, File as FileIcon } from 'lucide-react'
import apiClient from '@/services/api'

type Props = {
  documentId: string
  mimeType?: string
  /** 'sm' ≈ 40px, 'md' ≈ 56px, 'lg' ≈ 80px. Defaults to 'md'. */
  size?: 'sm' | 'md' | 'lg'
  className?: string
}

/**
 * Renders a thumbnail for a document. Three paths:
 *   - image/*           → fetch blob, serve via object URL
 *   - application/pdf   → fetch blob, render first page via pdfjs (dynamic import
 *                         so the ~1 MB worker is only loaded when a PDF appears)
 *   - anything else     → generic file icon
 *
 * Uses the authenticated axios client because plain <img src> can't attach the
 * Firebase bearer token that the backend expects on /documents/{id}/download.
 */
export default function DocumentThumbnail({ documentId, mimeType, size = 'md', className = '' }: Props) {
  const [src, setSrc] = useState<string | null>(null)
  const isImage = (mimeType ?? '').startsWith('image/')
  const isPdf = (mimeType ?? '').toLowerCase() === 'application/pdf'
  const dim = size === 'sm' ? 'w-10 h-10' : size === 'lg' ? 'w-20 h-20' : 'w-14 h-14'

  useEffect(() => {
    if (!isImage && !isPdf) {
      setSrc(null)
      return
    }
    let cancelled = false
    let objUrl: string | null = null
    ;(async () => {
      try {
        const res = await apiClient.get(`/documents/${documentId}/download`, { responseType: 'blob' })
        if (cancelled) return
        if (isImage) {
          objUrl = URL.createObjectURL(res.data as Blob)
          setSrc(objUrl)
          return
        }
        // PDF: render first page to a canvas and export as data URL.
        const pdfjs: any = await import('pdfjs-dist')
        // reason: pdfjs worker must be served separately. ?url gives us the static
        // asset path Vite produces; no CDN dependency, plays nice with the PWA
        // service worker (workbox precaches all bundled assets including this).
        const workerUrl = (await import('pdfjs-dist/build/pdf.worker.mjs?url')).default
        pdfjs.GlobalWorkerOptions.workerSrc = workerUrl

        const bytes = await (res.data as Blob).arrayBuffer()
        const pdf = await pdfjs.getDocument({ data: bytes }).promise
        const page = await pdf.getPage(1)
        const targetPx = size === 'sm' ? 80 : size === 'lg' ? 160 : 112
        const baseViewport = page.getViewport({ scale: 1 })
        const scale = targetPx / baseViewport.width
        const viewport = page.getViewport({ scale })
        const canvas = document.createElement('canvas')
        canvas.width = viewport.width
        canvas.height = viewport.height
        const ctx = canvas.getContext('2d')
        if (!ctx) return
        await page.render({ canvasContext: ctx, viewport }).promise
        if (cancelled) return
        setSrc(canvas.toDataURL('image/png'))
      } catch {
        /* ignore — fall back to icon */
      }
    })()
    return () => {
      cancelled = true
      if (objUrl) URL.revokeObjectURL(objUrl)
    }
  }, [documentId, isImage, isPdf, size])

  if (src) {
    return (
      <img
        src={src}
        alt=""
        className={`${dim} rounded object-cover shrink-0 bg-muted ${className}`}
      />
    )
  }

  const Icon = isPdf ? FileText : FileIcon
  return (
    <div
      className={`${dim} rounded bg-muted flex items-center justify-center shrink-0 ${className}`}
    >
      <Icon className="w-5 h-5 text-muted-foreground" />
    </div>
  )
}
