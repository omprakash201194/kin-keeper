import { Button } from '@/components/ui/button'
import { Upload, FileText } from 'lucide-react'

export default function DocumentsPage() {
  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold">Documents</h1>
          <p className="text-sm text-muted-foreground">Browse and manage family documents.</p>
        </div>
        <Button>
          <Upload className="w-4 h-4 mr-2" />
          Upload
        </Button>
      </div>

      {/* Placeholder empty state */}
      <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
        <FileText className="w-12 h-12 mb-4" />
        <p className="text-lg font-medium mb-1">No documents yet</p>
        <p className="text-sm">Upload your first document or use the chat to get started.</p>
      </div>
    </div>
  )
}
