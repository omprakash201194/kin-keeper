import { Button } from '@/components/ui/button'
import { UserPlus, Users } from 'lucide-react'

export default function MembersPage() {
  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold">Family Members</h1>
          <p className="text-sm text-muted-foreground">Manage people in your family vault.</p>
        </div>
        <Button>
          <UserPlus className="w-4 h-4 mr-2" />
          Add Member
        </Button>
      </div>

      {/* Placeholder empty state */}
      <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
        <Users className="w-12 h-12 mb-4" />
        <p className="text-lg font-medium mb-1">No members yet</p>
        <p className="text-sm">Add family members to start organizing their documents.</p>
      </div>
    </div>
  )
}
