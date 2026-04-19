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
 * Renders a thumbnail for a document. For image mime types, fetches the blob
 * through the authenticated axios client (plain <img src> can't attach the
 * Firebase bearer token) and serves it via a local object URL. Non-images fall
 * back to a generic file icon.
 *
 * The effect re-runs only if documentId or mimeType changes, so re-renders
 * caused by unrelated state changes don't refetch the blob.
 */
export default function DocumentThumbnail({ documentId, mimeType, size = 'md', className = '' }: Props) {
  const [src, setSrc] = useState<string | null>(null)
  const isImage = (mimeType ?? '').startsWith('image/')
  const dim = size === 'sm' ? 'w-10 h-10' : size === 'lg' ? 'w-20 h-20' : 'w-14 h-14'

  useEffect(() => {
    if (!isImage) {
      setSrc(null)
      return
    }
    let cancelled = false
    let objUrl: string | null = null
    ;(async () => {
      try {
        const res = await apiClient.get(`/documents/${documentId}/download`, { responseType: 'blob' })
        if (cancelled) return
        objUrl = URL.createObjectURL(res.data as Blob)
        setSrc(objUrl)
      } catch {
        /* ignore — fall back to icon */
      }
    })()
    return () => {
      cancelled = true
      if (objUrl) URL.revokeObjectURL(objUrl)
    }
  }, [documentId, isImage])

  if (src) {
    return (
      <img
        src={src}
        alt=""
        className={`${dim} rounded object-cover shrink-0 bg-muted ${className}`}
      />
    )
  }

  const Icon = mimeType && mimeType.startsWith('application/pdf') ? FileText : FileIcon
  return (
    <div
      className={`${dim} rounded bg-muted flex items-center justify-center shrink-0 ${className}`}
    >
      <Icon className="w-5 h-5 text-muted-foreground" />
    </div>
  )
}
